package dev.pi.mobile.debug

/**
 * Parsed debug launch extras. The parser lives in the debug source set (and a
 * null stub in release), so E2E tooling can drive the app without ever shipping
 * the auto-connect path in a release build.
 */
data class DebugLaunch(
    val host: String? = null,
    val pin: String? = null,
    val autoConnect: Boolean = false,
    val prompt: String? = null,
)
