package dev.pi.mobile.net

object CookieHeaders {
    fun find(setCookies: List<String>, name: String): String? {
        for (header in setCookies) {
            val pair = header.substringBefore(';').trim()
            val equals = pair.indexOf('=')
            if (equals <= 0) continue
            if (pair.substring(0, equals).trim() != name) continue
            val value = pair.substring(equals + 1).trim()
            if (value.isEmpty()) continue
            if (value.any { it == '\r' || it == '\n' }) continue
            if (value.length > 512) continue
            return value
        }
        return null
    }

    fun retryAfterSeconds(value: String?): Long? {
        val seconds = value?.trim()?.toLongOrNull() ?: return null
        return seconds.coerceIn(0, 3600)
    }
}
