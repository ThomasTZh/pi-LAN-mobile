package dev.pi.mobile.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A Pi host is reachable only on a private LAN address. This is the single gate
 * for manual entry and saved profiles; nothing in the app may contact a public
 * host on the user's behalf.
 */
object LocalNetworkPolicy {
    const val DEFAULT_PI_PORT = 7690

    data class Result(val baseUrl: String?, val error: String? = null) {
        val ok: Boolean get() = baseUrl != null
    }

    fun normalize(raw: String, defaultPort: Int = DEFAULT_PI_PORT): Result {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return Result(null, "Enter a server address.")
        if (trimmed.any { it.isWhitespace() }) return Result(null, "An address cannot contain spaces.")
        if (trimmed.any { it == '\r' || it == '\n' || it.code < 0x20 }) {
            return Result(null, "That address contains invalid characters.")
        }

        var candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val scheme = candidate.substringBefore("://").lowercase()
        if (scheme != "http" && scheme != "https") {
            return Result(null, "Only http:// and https:// addresses are supported.")
        }

        val afterScheme = candidate.substringAfter("://")
        val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
        if (authority.isEmpty()) return Result(null, "That address is missing a host.")
        if (authority.contains('@')) return Result(null, "Remove the username or password from the address.")

        val hasExplicitPort = if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            close >= 0 && authority.length > close + 1 && authority[close + 1] == ':'
        } else {
            authority.count { it == ':' } == 1
        }
        if (!hasExplicitPort) {
            val suffix = afterScheme.substring(authority.length)
            candidate = "$scheme://$authority:$defaultPort$suffix"
        }

        val url: HttpUrl = candidate.toHttpUrlOrNull()
            ?: return Result(null, "That doesn't look like a valid address.")
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) {
            return Result(null, "Remove the username or password from the address.")
        }

        val host = normalizedHost(url)
        if (host.isEmpty()) return Result(null, "That address is missing a host.")
        if (!isPrivateHost(host)) {
            return Result(
                null,
                "Use a private LAN address (192.168.x.x, 10.x.x.x, 172.16-31.x.x, a local IPv6 address) or a .local name.",
            )
        }

        val hostPart = if (host.contains(':')) "[$host]" else host
        return Result("${url.scheme}://$hostPart:${url.port}")
    }

    fun normalizedHost(url: HttpUrl): String {
        var host = url.host.trim().lowercase()
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length - 1)
        return host.removeSuffix(".")
    }

    fun isPrivateHost(rawHost: String): Boolean {
        val host = rawHost.trim().lowercase().removeSuffix(".")
        if (host == "localhost" || host == "::1") return true
        if (host.startsWith("::ffff:")) return isPrivateV4(host.substringAfter("::ffff:"))
        if (host.contains(':')) return isPrivateV6(host)
        if (looksLikeIpv4(host)) return isPrivateV4(host)
        // Single-label hostnames and mDNS names resolve only on the local link.
        if (!host.contains('.')) return true
        return host.endsWith(".local")
    }

    private fun looksLikeIpv4(host: String): Boolean =
        host.split('.').let { parts -> parts.size == 4 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) } }

    private fun isPrivateV4(host: String): Boolean {
        val numbers = parseV4(host) ?: return false
        val a = numbers[0]
        val b = numbers[1]
        return a == 10 ||
            (a == 127) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254)
    }

    private fun parseV4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val numbers = IntArray(4)
        for (index in 0 until 4) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3 || !part.all(Char::isDigit)) return null
            val value = part.toIntOrNull() ?: return null
            if (value !in 0..255) return null
            numbers[index] = value
        }
        return numbers
    }

    private fun isPrivateV6(host: String): Boolean {
        val address = host.removePrefix("[").removeSuffix("]")
        if (address == "::1") return true
        // Reject anything that is not an IPv6 literal before asking InetAddress,
        // which would otherwise perform a DNS lookup for a malformed name.
        if (!address.matches(Regex("^[0-9a-fA-F:.]+$"))) return false
        val parsed = try {
            java.net.InetAddress.getByName(address)
        } catch (_: Exception) {
            return false
        }
        if (parsed is java.net.Inet4Address) {
            return isPrivateV4(parsed.hostAddress ?: return false)
        }
        val bytes = parsed.address
        if (bytes.size != 16) return false
        if (parsed.isLoopbackAddress) return true
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        // fc00::/7 (unique local) and fe80::/10 (link local).
        if (first and 0xfe == 0xfc) return true
        if (first == 0xfe && second and 0xc0 == 0x80) return true
        return false
    }
}
