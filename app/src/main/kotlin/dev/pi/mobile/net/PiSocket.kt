package dev.pi.mobile.net

import dev.pi.mobile.proto.Commands
import dev.pi.mobile.proto.boolOr
import dev.pi.mobile.proto.errorText
import dev.pi.mobile.proto.intOr
import dev.pi.mobile.proto.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Thrown when the socket cannot be opened or the host rejects the hello. */
class PiSocketException(
    message: String,
    val unauthorized: Boolean = false,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * One Pi WebSocket. The host speaks JSONL in text frames: a client hello first,
 * then commands/events. Correlated replies are resolved by meta id or response
 * command; everything else is handed to the listener as a raw record so the
 * pure [dev.pi.mobile.session.ChatReducer] can consume it.
 */
class PiSocket(
    private val client: OkHttpClient = defaultClient(),
) {
    data class Hello(val pid: Int?, val ok: Boolean, val error: String?, val raw: JsonObject)

    data class Reply(val ok: Boolean, val data: kotlinx.serialization.json.JsonElement? = null, val error: String? = null)

    interface Listener {
        /** A record that no pending request is waiting for (events, dialogs, child). */
        fun onRecord(record: JsonObject) {}

        fun onClosed(reason: String) {}

        fun onFailure(error: Throwable) {}
    }

    private val pendingMeta = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<JsonObject?>>()
    private val commandLanes = ConcurrentHashMap<String, MutableList<String>>()
    private val ids = AtomicLong(0)

    @Volatile
    private var webSocket: WebSocket? = null

    @Volatile
    private var listener: Listener? = null

    @Volatile
    private var helloSeen = false

    @Volatile
    private var deliberateClose = false

    @Volatile
    private var failure: PiSocketException? = null

    @Volatile
    private var open = false

    val isOpen: Boolean get() = open

    fun setListener(value: Listener?) {
        listener = value
    }

    /**
     * Opens the socket and performs the hello handshake. Returns only after the
     * host answered `{"type":"hello","ok":true,...}`.
     */
    suspend fun connect(
        baseUrl: String,
        cookie: String,
        cwd: String? = null,
        session: String? = null,
        extraArgs: List<String> = emptyList(),
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Hello {
        close()
        val url = wsUrl(baseUrl) ?: throw PiSocketException("That address cannot host a WebSocket.")
        deliberateClose = false
        failure = null
        helloSeen = false
        open = false
        val hello = CompletableDeferred<Hello>()
        val request = Request.Builder()
            .url(url)
            .header("Cookie", "${PiHttp.COOKIE_NAME}=$cookie")
            .build()
        val socket = client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    open = true
                    webSocket.send(Commands.hello(cwd, session, extraArgs).toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val record = parseObject(text) ?: return
                    if (!helloSeen && record.str("type") == "hello") {
                        helloSeen = true
                        hello.complete(parseHello(record))
                        return
                    }
                    dispatch(record)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL_CLOSURE, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    open = false
                    failPending()
                    if (helloSeen) {
                        if (!deliberateClose) listener?.onClosed(reason.ifBlank { "closed ($code)" })
                    } else if (!hello.isCompleted) {
                        hello.complete(failedHello("The host closed the socket before hello."))
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    open = false
                    val error = PiSocketException(
                        message = describeFailure(response?.code, t),
                        unauthorized = response?.code == 401,
                        cause = t,
                    )
                    failure = error
                    if (!hello.isCompleted) hello.complete(failedHello(error.message ?: "The connection failed."))
                    failPending()
                    if (helloSeen && !deliberateClose) listener?.onFailure(error)
                }
            },
        )
        webSocket = socket
        val reply = withTimeoutOrNull(timeoutMs) { hello.await() }
        if (reply == null) {
            close()
            throw PiSocketException("The desktop did not answer the WebSocket handshake.")
        }
        val socketFailure = failure
        if (socketFailure != null) {
            close()
            throw socketFailure
        }
        if (!reply.ok) {
            close()
            throw PiSocketException(reply.error ?: "The host rejected the hello handshake.")
        }
        return reply
    }

    /** Sends one JSON object; false when the socket is not open. */
    fun send(record: JsonObject): Boolean {
        val socket = webSocket ?: return false
        return socket.send(record.toString())
    }

    /**
     * Meta request with an id-correlated reply. Returns `ok=false` with a user
     * facing message on timeout; never throws for a normal protocol failure.
     */
    suspend fun meta(
        action: String,
        payload: JsonObject? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Reply {
        val id = "m${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject?>()
        pendingMeta[id] = deferred
        if (!send(Commands.meta(id, action, payload))) {
            pendingMeta.remove(id)
            return Reply(ok = false, error = "Not connected to Pi.")
        }
        val record = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (record == null) {
            pendingMeta.remove(id)
            return Reply(
                ok = false,
                error = if (isOpen) "The desktop did not answer in ${timeoutMs / 1000}s." else "The connection closed.",
            )
        }
        val ok = record.boolOr("ok") ?: false
        return Reply(ok = ok, data = record["data"], error = record.errorText())
    }

    /**
     * Command request from `prompt`/`get_state`/... Correlated by the RPC id the
     * Pi child echoes in its response; a response without an id still matches
     * the oldest waiting request for its command name.
     */
    suspend fun command(
        name: String,
        payload: JsonObject? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): Reply {
        val id = "c${ids.incrementAndGet()}"
        val deferred = CompletableDeferred<JsonObject?>()
        pendingCommands[id] = deferred
        synchronized(commandLanes) { commandLanes.getOrPut(name) { mutableListOf() }.add(id) }
        val body = buildJsonObject {
            payload?.forEach { (key, value) -> if (key != "type" && key != "id") put(key, value) }
            put("id", id)
        }
        if (!send(Commands.command(name, body))) {
            removePendingCommand(id, name)
            return Reply(ok = false, error = "Not connected to Pi.")
        }
        val record = withTimeoutOrNull(timeoutMs) { deferred.await() }
        if (record == null) {
            removePendingCommand(id, name)
            return Reply(
                ok = false,
                error = if (isOpen) "The desktop did not answer in ${timeoutMs / 1000}s." else "The connection closed.",
            )
        }
        val ok = record.boolOr("success") ?: false
        return Reply(ok = ok, data = record["data"], error = record.errorText())
    }

    /** Raw fire-and-forget send, used for extension_ui_response. */
    fun sendRaw(record: JsonObject): Boolean = send(record)

    fun close(code: Int = NORMAL_CLOSURE, reason: String = "client closed") {
        deliberateClose = true
        val socket = webSocket
        webSocket = null
        open = false
        socket?.close(code, reason)
        failPending()
    }

    private fun dispatch(record: JsonObject) {
        when (record.str("type")) {
            "meta" -> {
                val id = record.str("id")
                val deferred = id?.let { pendingMeta.remove(it) }
                if (deferred != null) deferred.complete(record) else listener?.onRecord(record)
            }

            "response" -> {
                val command = record.str("command")
                val id = record.str("id")
                val deferred = when {
                    id != null -> pendingCommands.remove(id)?.also { removeIdFromLane(command, id) }
                    else -> {
                        val laneId = synchronized(commandLanes) {
                            command?.let { name -> commandLanes[name]?.removeFirstOrNull() }
                        }
                        laneId?.let { pendingCommands.remove(it) }
                    }
                }
                if (deferred != null) deferred.complete(record) else listener?.onRecord(record)
            }

            else -> listener?.onRecord(record)
        }
    }

    private fun removePendingCommand(id: String, name: String) {
        pendingCommands.remove(id)
        synchronized(commandLanes) {
            commandLanes[name]?.remove(id)
            if (commandLanes[name]?.isEmpty() == true) commandLanes.remove(name)
        }
    }

    private fun removeIdFromLane(command: String?, id: String) {
        synchronized(commandLanes) {
            if (command != null) {
                commandLanes[command]?.remove(id)
                if (commandLanes[command]?.isEmpty() == true) commandLanes.remove(command)
            } else {
                for ((name, lane) in commandLanes) {
                    if (lane.remove(id)) {
                        if (lane.isEmpty()) commandLanes.remove(name)
                        break
                    }
                }
            }
        }
    }

    private fun failPending() {
        for (deferred in pendingMeta.values) deferred.complete(null)
        pendingMeta.clear()
        for (deferred in pendingCommands.values) deferred.complete(null)
        pendingCommands.clear()
        synchronized(commandLanes) { commandLanes.clear() }
    }

    private fun parseHello(record: JsonObject): Hello = Hello(
        pid = record.intOr("pid"),
        ok = record.boolOr("ok") ?: true,
        error = record.errorText(),
        raw = record,
    )

    private fun failedHello(message: String): Hello = Hello(
        pid = null,
        ok = false,
        error = message,
        raw = buildJsonObject { },
    )

    private fun describeFailure(code: Int?, error: Throwable): String = when {
        code == 401 -> "The host rejected the session cookie. Sign in again."
        code != null -> "The host refused the WebSocket (HTTP $code)."
        error is java.net.SocketTimeoutException -> "The WebSocket connection timed out."
        error is java.net.ConnectException -> "The host refused the WebSocket connection."
        error is java.net.UnknownHostException -> "The host name could not be resolved."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "The WebSocket connection failed."
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 15_000L
        const val NORMAL_CLOSURE = 1000

        /** http(s)://host:port -> ws(s)://host:port/ */
        fun wsUrl(baseUrl: String): String? {
            val url = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
            val scheme = when (url.scheme.lowercase()) {
                "https" -> "wss"
                "http" -> "ws"
                else -> return null
            }
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            return "$scheme://$host:${url.port}/"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            // WebSocket frames arrive on their own schedule; liveness is kept by pings.
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }
}

private fun parseObject(text: String): JsonObject? =
    runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
