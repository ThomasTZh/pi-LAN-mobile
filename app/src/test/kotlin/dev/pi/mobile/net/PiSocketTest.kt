package dev.pi.mobile.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PiSocketTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    private fun upgrade(handler: (WebSocket, String) -> Unit): MockResponse =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) = handler(webSocket, text)
            },
        )

    private fun helloAndReply(pid: Int = 42): (WebSocket, String) -> Unit = { socket, text ->
        if (text.contains("\"hello\"")) socket.send("""{"type":"hello","ok":true,"pid":$pid}""")
    }

    @Test
    fun helloHandshakeReturnsPidAndSendsCookie() = runBlocking {
        server.enqueue(upgrade(helloAndReply()))
        val socket = PiSocket()
        try {
            val hello = socket.connect(baseUrl(), "session-cookie", cwd = "/home/pi/project")
            assertTrue(hello.ok)
            assertEquals(42, hello.pid)
            assertEquals("pi_rpc_web_session=session-cookie", server.takeRequest().getHeader("Cookie"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun helloFailureCarriesTheHostMessage() {
        server.enqueue(
            upgrade { socket, text ->
                if (text.contains("\"hello\"")) {
                    socket.send("""{"type":"hello","ok":false,"error":"cwd is required"}""")
                }
            },
        )
        val error = assertThrows(PiSocketException::class.java) {
            runBlocking { PiSocket().connect(baseUrl(), "c", cwd = null) }
        }
        assertEquals("cwd is required", error.message)
    }

    @Test
    fun unauthorizedHandshakeIsFlagged() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        val error = assertThrows(PiSocketException::class.java) {
            runBlocking { PiSocket().connect(baseUrl(), "stale", cwd = "/p") }
        }
        assertTrue("expected unauthorized, got ${error.message}", error.unauthorized)
    }

    @Test
    fun metaCorrelatesById() = runBlocking {
        server.enqueue(
            upgrade { socket, text ->
                when {
                    text.contains("\"hello\"") -> socket.send("""{"type":"hello","ok":true,"pid":1}""")
                    text.contains("\"action\":\"ping\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                        socket.send("""{"type":"meta","id":"$id","ok":true,"data":{"pong":true}}""")
                    }
                }
            },
        )
        val socket = PiSocket()
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val reply = socket.meta("ping")
            assertTrue(reply.ok)
            assertEquals("""{"pong":true}""", reply.data.toString())
        } finally {
            socket.close()
        }
    }

    @Test
    fun commandCorrelatesByResponseCommand() = runBlocking {
        server.enqueue(
            upgrade { socket, text ->
                when {
                    text.contains("\"hello\"") -> socket.send("""{"type":"hello","ok":true,"pid":1}""")
                    text.contains("\"type\":\"get_state\"") -> {
                        val id = Regex("\"id\":\"([^\"]+)\"").find(text)?.groupValues?.get(1)
                        socket.send(
                            """{"type":"response","id":"$id","command":"get_state","success":true,"data":{"model":{"provider":"llama","id":"qwen3"}}}""",
                        )
                    }
                }
            },
        )
        val socket = PiSocket()
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val reply = socket.command("get_state")
            assertTrue(reply.ok)
            val data = Json.parseToJsonElement(reply.data.toString()).toString()
            assertTrue(data.contains("qwen3"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun commandFailureCarriesHostError() = runBlocking {
        server.enqueue(
            upgrade { socket, text ->
                when {
                    text.contains("\"hello\"") -> socket.send("""{"type":"hello","ok":true,"pid":1}""")
                    text.contains("\"type\":\"abort\"") -> socket.send(
                        """{"type":"response","command":"abort","success":false,"error":{"message":"no active run"}}""",
                    )
                }
            },
        )
        val socket = PiSocket()
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val reply = socket.command("abort")
            assertFalse(reply.ok)
            assertEquals("no active run", reply.error)
        } finally {
            socket.close()
        }
    }

    @Test
    fun commandTimeoutReturnsFriendlyError() = runBlocking {
        server.enqueue(upgrade(helloAndReply()))
        val socket = PiSocket()
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val reply = socket.command("get_state", timeoutMs = 250)
            assertFalse(reply.ok)
            assertTrue(reply.error.orEmpty().contains("did not answer"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun unmatchedRecordsReachTheListener() = runBlocking {
        val event = CompletableDeferred<String>()
        server.enqueue(
            upgrade { socket, text ->
                if (text.contains("\"hello\"")) {
                    socket.send("""{"type":"hello","ok":true,"pid":1}""")
                    socket.send("""{"type":"agent_start"}""")
                }
            },
        )
        val socket = PiSocket()
        socket.setListener(
            object : PiSocket.Listener {
                override fun onRecord(record: kotlinx.serialization.json.JsonObject) {
                    if (!event.isCompleted) event.complete(record.toString())
                }
            },
        )
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val received = withTimeout(3_000) { event.await() }
            assertTrue(received.contains("agent_start"))
        } finally {
            socket.close()
        }
    }

    @Test
    fun closingSocketNotifiesTheListener() = runBlocking {
        val closed = CompletableDeferred<String>()
        server.enqueue(
            upgrade { socket, text ->
                if (text.contains("\"hello\"")) {
                    socket.send("""{"type":"hello","ok":true,"pid":1}""")
                    socket.close(1000, "server done")
                }
            },
        )
        val socket = PiSocket()
        socket.setListener(
            object : PiSocket.Listener {
                override fun onClosed(reason: String) {
                    if (!closed.isCompleted) closed.complete(reason)
                }
            },
        )
        try {
            socket.connect(baseUrl(), "c", cwd = "/p")
            val reason = withTimeout(3_000) { closed.await() }
            assertNotNull(reason)
        } finally {
            socket.close()
        }
    }

    @Test
    fun notConnectedCommandsFailFast() = runBlocking {
        val socket = PiSocket()
        val meta = socket.meta("ping")
        val command = socket.command("get_state")
        assertFalse(meta.ok)
        assertFalse(command.ok)
        assertEquals("Not connected to Pi.", meta.error)
    }

    @Test
    fun buildsWebSocketUrls() {
        assertEquals("ws://192.168.1.50:7690/", PiSocket.wsUrl("http://192.168.1.50:7690"))
        val secure = PiSocket.wsUrl("https://[fd00::1]:7690")
        assertNotNull(secure)
        assertTrue(secure!!.startsWith("wss://"))
        assertTrue(secure.endsWith(":7690/"))
        assertNull(PiSocket.wsUrl("ftp://192.168.0.1:7690"))
        assertNull(PiSocket.wsUrl(""))
    }
}
