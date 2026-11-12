package dev.pi.mobile.session

import dev.pi.mobile.net.PiHttp
import dev.pi.mobile.net.PiSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Review F4: the reconnect loop is the most failure-prone state machine in the
 * app and was only code-reviewed. This drives it against MockWebServer:
 *
 *  1. login #1 -> cookie1, WebSocket upgrade #1 accepted, hello #1 answered;
 *  2. the host "restarts": the server end of the socket closes;
 *  3. the reconnect reuses cookie1, the restarted host rejects it with 401;
 *  4. exactly one re-login (cookie2) and one re-hello, one live socket.
 */
class PiConnectionReconnectTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private var connection: PiConnection? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val loginCount = AtomicInteger(0)
    private val helloCount = AtomicInteger(0)
    private val wsAttempts = AtomicInteger(0)
    private val rejectedUpgrades = Collections.synchronizedList(mutableListOf<Int>())
    private val liveSockets = Collections.synchronizedList(mutableListOf<WebSocket>())
    private val states = Collections.synchronizedList(mutableListOf<PiConnection.State>())
    private val observedRequests = Collections.synchronizedList(mutableListOf<String>())

    @Volatile
    private var firstSocket: WebSocket? = null

    @Volatile
    private var pid = 100

    @Before
    fun setUp() {
        server = MockWebServer()
        // Mirror PiHttp.defaultClient: the 303 login answer must never be
        // followed, otherwise the client lands on the WebSocket upgrade (101).
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                observedRequests.add("${request.method} ${request.path}")
                if (request.method == "POST") {
                    val attempt = loginCount.incrementAndGet()
                    return MockResponse()
                        .setResponseCode(303)
                        .addHeader("Location", "/")
                        .addHeader("Set-Cookie", "pi_rpc_web_session=cookie$attempt; Path=/; HttpOnly")
                }
                // The restarted host accepts only the new session cookie: the
                // second upgrade attempt still carries the cookie of the dead
                // process and must be rejected.
                val attempt = wsAttempts.incrementAndGet()
                if (attempt == 2) {
                    rejectedUpgrades.add(401)
                    return MockResponse().setResponseCode(401)
                }
                return MockResponse().withWebSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            liveSockets.add(webSocket)
                            if (firstSocket == null) firstSocket = webSocket
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (text.contains("\"hello\"")) {
                                helloCount.incrementAndGet()
                                webSocket.send("""{"type":"hello","ok":true,"pid":${pid++}}""")
                            }
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            liveSockets.remove(webSocket)
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            liveSockets.remove(webSocket)
                        }
                    },
                )
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        connection?.close()
        scope.cancel()
        runCatching { server.shutdown() }
    }

    private fun newConnection(): PiConnection = PiConnection(
        http = PiHttp(client),
        socketFactory = { PiSocket(client) },
    ).also { connection = it }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun staleCookieTriggersExactlyOneReloginAndOneRehello() = runBlocking {
        val conn = newConnection()
        val collector = scope.launch { conn.state.collect { states.add(it) } }

        val first = conn.connect(baseUrl(), "123456")
        assertTrue("initial connect: $first (requests: $observedRequests)", first is PiConnection.ConnectResult.Ok)
        assertEquals("one login", 1, loginCount.get())
        assertEquals("one hello", 1, helloCount.get())
        assertEquals("one live socket", 1, liveSockets.size)
        assertTrue(conn.isConnected)

        // The host process dies and the OS closes the socket.
        withTimeout(5_000) {
            while (firstSocket == null) Thread.sleep(10)
        }
        firstSocket!!.close(1001, "host restart")

        val reconnected = withTimeout(20_000) {
            conn.state.first { it is PiConnection.State.Connected && it.reconnected }
        }
        assertTrue(reconnected is PiConnection.State.Connected)
        assertEquals("exactly one re-login", 2, loginCount.get())
        assertEquals("exactly one re-hello", 2, helloCount.get())
        assertEquals("the stale cookie was rejected once", 1, rejectedUpgrades.count { it == 401 })
        assertEquals("three upgrade attempts: initial, rejected, re-hello", 3, wsAttempts.get())
        assertEquals("one live socket after the reconnect", 1, liveSockets.size)
        assertTrue(conn.isConnected)

        collector.cancel()
        assertTrue(
            "the reconnecting state has to be published before the reconnect",
            states.any { it is PiConnection.State.Reconnecting },
        )
    }

    @Test
    fun aRejectedPinDuringReconnectFailsInsteadOfLooping() = runBlocking {
        val conn = newConnection()
        assertTrue(conn.connect(baseUrl(), "123456") is PiConnection.ConnectResult.Ok)
        // Stop accepting logins after the initial one: the host is gone for good.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401)
        }
        withTimeout(5_000) {
            while (firstSocket == null) Thread.sleep(10)
        }
        firstSocket!!.close(1001, "host restart")

        val failed = withTimeout(20_000) {
            conn.state.first { it is PiConnection.State.Failed }
        }
        assertTrue(failed is PiConnection.State.Failed)
    }
}
