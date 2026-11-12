package dev.pi.mobile.session

import dev.pi.mobile.net.LocalNetworkPolicy
import dev.pi.mobile.net.PiHttp
import dev.pi.mobile.net.PiSocket
import dev.pi.mobile.net.ReconnectBackoff
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/**
 * The single Pi WebSocket of the app process (PLAN.md 3). The activity and the
 * foreground service share this object: logs in once, opens one socket, and
 * reconnects with 0.5/1/2/4/8 s backoff while reconnection is enabled.
 *
 * The session cookie lives only in this object's memory; it is never written to
 * disk or logged.
 */
class PiConnection(
    private val http: PiHttp = PiHttp(),
    private val socketFactory: () -> PiSocket = { PiSocket() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    sealed interface State {
        data object Idle : State
        data object Connecting : State
        data class Connected(val pid: Int?, val baseUrl: String, val reconnected: Boolean = false) : State
        data class Reconnecting(val attempt: Int, val delayMs: Long, val baseUrl: String) : State
        data class Failed(val message: String) : State
        data object Closed : State
    }

    sealed interface ConnectResult {
        data class Ok(val pid: Int?, val baseUrl: String, val reconnected: Boolean = false) : ConnectResult
        data class Failed(
            val message: String,
            val rejected: Boolean = false,
            val lockedSeconds: Long? = null,
        ) : ConnectResult
    }

    private data class Target(val baseUrl: String, val pin: String, val cwd: String?, val session: String?)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _records = MutableSharedFlow<JsonObject>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Every record no pending request consumed: events, dialogs, child notices. */
    val records: SharedFlow<JsonObject> = _records.asSharedFlow()

    private val connectMutex = Mutex()

    @Volatile
    private var socket: PiSocket? = null

    @Volatile
    private var cookie: String? = null

    @Volatile
    private var target: Target? = null

    @Volatile
    private var reconnectJob: Job? = null

    @Volatile
    private var reconnectEnabled = true

    @Volatile
    private var lastPid: Int? = null

    val isConnected: Boolean get() = socket?.isOpen == true

    val pid: Int? get() = lastPid

    fun setReconnectEnabled(enabled: Boolean) {
        reconnectEnabled = enabled
        if (!enabled) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
    }

    /**
     * Logs in with [pin] and opens a hello handshake. A previous socket, if any,
     * is closed first; a failed connect leaves no socket behind.
     */
    suspend fun connect(
        address: String,
        pin: String,
        cwd: String? = null,
        session: String? = null,
    ): ConnectResult {
        val policy = LocalNetworkPolicy.normalize(address)
        if (!policy.ok) return ConnectResult.Failed(policy.error ?: "That address is not on the LAN.")
        val baseUrl = policy.baseUrl ?: return ConnectResult.Failed("That address is not on the LAN.")

        reconnectJob?.cancel()
        reconnectJob = null
        reconnectEnabled = true
        closeSocket()
        _state.value = State.Connecting

        return connectMutex.withLock {
            val login = http.login(baseUrl, pin)
            if (!login.ok) {
                val message = login.error ?: "The host did not accept the PIN."
                _state.value = State.Failed(message)
                return@withLock ConnectResult.Failed(message, login.rejected, login.lockedSeconds)
            }
            cookie = login.cookie
            val nextTarget = Target(baseUrl = baseUrl, pin = pin, cwd = cwd, session = session)
            target = nextTarget
            try {
                val hello = openSocket(nextTarget, login.cookie ?: "")
                lastPid = hello.pid
                _state.value = State.Connected(pid = hello.pid, baseUrl = baseUrl)
                ConnectResult.Ok(pid = hello.pid, baseUrl = baseUrl)
            } catch (error: Exception) {
                socket = null
                cookie = null
                target = nextTarget
                val message = error.message ?: "The WebSocket connection failed."
                _state.value = State.Failed(message)
                ConnectResult.Failed(message)
            }
        }
    }

    /** Manual retry with the last target. */
    suspend fun reconnectNow(): ConnectResult {
        val current = target ?: return ConnectResult.Failed("No host to reconnect to.")
        reconnectEnabled = true
        _state.value = State.Connecting
        return connectMutex.withLock { reconnectOnce(current) }
    }

    fun close() {
        reconnectEnabled = false
        reconnectJob?.cancel()
        reconnectJob = null
        target = null
        cookie = null
        lastPid = null
        closeSocket()
        _state.value = State.Closed
    }

    suspend fun meta(action: String, payload: JsonObject? = null): PiSocket.Reply =
        socket?.meta(action, payload) ?: PiSocket.Reply(ok = false, error = "Not connected to Pi.")

    suspend fun command(name: String, payload: JsonObject? = null): PiSocket.Reply =
        socket?.command(name, payload) ?: PiSocket.Reply(ok = false, error = "Not connected to Pi.")

    fun sendRaw(record: JsonObject): Boolean = socket?.sendRaw(record) ?: false

    // --------------------------------------------------------------- internals

    private fun closeSocket() {
        val current = socket
        socket = null
        current?.close()
    }

    private suspend fun openSocket(target: Target, cookieValue: String): PiSocket.Hello {
        val next = socketFactory()
        next.setListener(
            object : PiSocket.Listener {
                override fun onRecord(record: JsonObject) {
                    _records.tryEmit(record)
                }

                override fun onClosed(reason: String) {
                    handleDrop(next, reason.ifBlank { "The connection closed." })
                }

                override fun onFailure(error: Throwable) {
                    handleDrop(next, error.message ?: "The connection to Pi failed.")
                }
            },
        )
        val hello = next.connect(
            baseUrl = target.baseUrl,
            cookie = cookieValue,
            cwd = target.cwd,
            session = target.session,
        )
        socket = next
        return hello
    }

    private fun handleDrop(source: PiSocket, message: String) {
        if (socket !== source) return
        socket = null
        if (!reconnectEnabled) {
            _state.value = State.Closed
            return
        }
        val current = target ?: return
        if (reconnectJob?.isActive == true) return
        _state.value = State.Reconnecting(attempt = 0, delayMs = ReconnectBackoff.delayFor(0), baseUrl = current.baseUrl)
        reconnectJob = scope.launch { reconnectLoop(current) }
    }

    private suspend fun reconnectLoop(current: Target) {
        var attempt = 0
        while (reconnectEnabled && currentCoroutineContext().isActive) {
            val delayMs = ReconnectBackoff.delayFor(attempt)
            _state.value = State.Reconnecting(attempt = attempt, delayMs = delayMs, baseUrl = current.baseUrl)
            delay(delayMs)
            if (!reconnectEnabled) break
            val result = connectMutex.withLock {
                if (socket?.isOpen == true) ConnectResult.Ok(lastPid, current.baseUrl, reconnected = true)
                else reconnectOnce(current)
            }
            when (result) {
                is ConnectResult.Ok -> {
                    _state.value = State.Connected(result.pid, result.baseUrl, reconnected = true)
                    return
                }
                is ConnectResult.Failed -> {
                    if (result.rejected) {
                        _state.value = State.Failed(result.message)
                        return
                    }
                    attempt++
                }
            }
        }
    }

    /**
     * One reconnect attempt: reuse the cookie, and if the host rejects it do a
     * single re-login with the saved PIN (PLAN.md 6).
     */
    private suspend fun reconnectOnce(current: Target): ConnectResult {
        val existingCookie = cookie
        if (existingCookie != null) {
            try {
                val hello = openSocket(current, existingCookie)
                lastPid = hello.pid
                return ConnectResult.Ok(hello.pid, current.baseUrl, reconnected = true)
            } catch (_: Exception) {
                socket = null
            }
        }
        val login = http.login(current.baseUrl, current.pin)
        if (!login.ok) {
            cookie = null
            return ConnectResult.Failed(
                message = login.error ?: "The host did not accept the saved PIN.",
                rejected = login.rejected || login.lockedSeconds != null,
                lockedSeconds = login.lockedSeconds,
            )
        }
        cookie = login.cookie
        return try {
            val hello = openSocket(current, login.cookie ?: "")
            lastPid = hello.pid
            ConnectResult.Ok(hello.pid, current.baseUrl, reconnected = true)
        } catch (error: Exception) {
            socket = null
            ConnectResult.Failed(error.message ?: "The WebSocket connection failed.")
        }
    }

    companion object {
        @Volatile
        private var singleton: PiConnection? = null

        /** Process-wide instance shared by the activity and [service.PiAlertsService]. */
        fun shared(): PiConnection = singleton ?: synchronized(this) {
            singleton ?: PiConnection().also { singleton = it }
        }
    }
}
