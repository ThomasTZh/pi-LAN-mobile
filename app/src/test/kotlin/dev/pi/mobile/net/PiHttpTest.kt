package dev.pi.mobile.net

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PiHttpTest {
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

    @Test
    fun postsPinWithoutSpacesAndKeepsSessionCookie() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(303)
                .addHeader("Location", "/")
                .addHeader(
                    "Set-Cookie",
                    "pi_rpc_web_session=abc123DEF; HttpOnly; SameSite=Strict; Path=/",
                ),
        )
        val result = PiHttp().login(baseUrl(), "123 456")
        assertTrue(result.ok)
        assertEquals("abc123DEF", result.cookie)
        assertEquals(303, result.status)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/login", request.path)
        assertEquals("pin=123456", request.body.readUtf8())
        assertEquals("application/x-www-form-urlencoded", request.getHeader("Content-Type"))
    }

    @Test
    fun rejectedPinOnLoginPage() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("<form>login</form>"))
        val result = PiHttp().login(baseUrl(), "000000")
        assertFalse(result.ok)
        assertTrue(result.rejected)
        assertEquals("That PIN was not accepted.", result.error)
    }

    @Test
    fun rejectedPinOnUnauthorized() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("no"))
        val result = PiHttp().login(baseUrl(), "000000")
        assertFalse(result.ok)
        assertTrue(result.rejected)
    }

    @Test
    fun lockoutUsesRetryAfter() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "42"))
        val result = PiHttp().login(baseUrl(), "000000")
        assertFalse(result.ok)
        assertEquals(42L, result.lockedSeconds)
        assertEquals("PIN locked, wait 30 s.", result.error)
    }

    @Test
    fun invalidPinNeverReachesTheNetwork() = runBlocking {
        val result = PiHttp().login(baseUrl(), "12")
        assertFalse(result.ok)
        assertTrue(result.rejected)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun redirectWithoutCookieIsNotSuccess() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/"))
        val result = PiHttp().login(baseUrl(), "123456")
        assertFalse(result.ok)
        assertNotNull(result.error)
    }

    @Test
    fun unreachableHostIsReported() = runBlocking {
        val url = baseUrl()
        server.shutdown()
        val result = PiHttp().login(url, "123456")
        assertFalse(result.ok)
        assertNotNull(result.error)
    }
}
