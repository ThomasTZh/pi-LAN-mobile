package dev.pi.mobile.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * The only code that talks to the Pi LAN host's login endpoint. It never logs
 * the PIN and never follows a redirect: a 303 is the success signal that
 * carries the session cookie (PLAN.md 5.3).
 */
class PiHttp(
    private val client: OkHttpClient = defaultClient(),
) {
    data class LoginResult(
        val ok: Boolean,
        val cookie: String? = null,
        val status: Int? = null,
        val rejected: Boolean = false,
        val lockedSeconds: Long? = null,
        val error: String? = null,
    )

    suspend fun login(baseUrl: String, pin: String): LoginResult = withContext(Dispatchers.IO) {
        val digits = PinRules.normalize(pin)
            ?: return@withContext LoginResult(ok = false, rejected = true, error = "Enter the 6-digit PIN.")
        val form = FormBody.Builder().add("pin", digits).build()
        val request = Request.Builder()
            .url("$baseUrl/login")
            .post(form)
            .header("Accept", "text/html")
            .header("Cache-Control", "no-cache")
            .header("Origin", baseUrl)
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val cookie = CookieHeaders.find(response.headers.values("Set-Cookie"), COOKIE_NAME)
                when (response.code) {
                    in 300..399 -> if (cookie != null) {
                        LoginResult(ok = true, cookie = cookie, status = response.code)
                    } else {
                        LoginResult(
                            ok = false,
                            status = response.code,
                            error = "The host accepted the PIN but sent no session cookie.",
                        )
                    }

                    401, 403 -> LoginResult(
                        ok = false,
                        status = response.code,
                        rejected = true,
                        error = "That PIN was not accepted.",
                    )

                    429 -> LoginResult(
                        ok = false,
                        status = response.code,
                        lockedSeconds = CookieHeaders.retryAfterSeconds(response.header("Retry-After")) ?: 30,
                        error = "PIN locked, wait 30 s.",
                    )

                    200 -> LoginResult(
                        ok = false,
                        status = response.code,
                        rejected = true,
                        error = "That PIN was not accepted.",
                    )

                    else -> LoginResult(
                        ok = false,
                        status = response.code,
                        error = "Unexpected response from the host (${response.code}).",
                    )
                }
            }
        } catch (error: IOException) {
            LoginResult(ok = false, error = describe(error))
        }
    }

    private fun describe(error: IOException): String = when (error) {
        is SocketTimeoutException -> "The host did not answer. Check the address and Wi-Fi."
        is ConnectException -> "The host refused the connection. Is the Pi host running?"
        is UnknownHostException -> "The host name could not be resolved."
        else -> error.message?.takeIf { it.isNotBlank() } ?: "The host could not be reached."
    }

    companion object {
        const val COOKIE_NAME = "pi_rpc_web_session"

        /**
         * Short timeouts and a zero-idle pool: the LAN host closes keep-alives
         * aggressively, and a reused stale socket must never delay a login.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .retryOnConnectionFailure(true)
            .build()
    }
}
