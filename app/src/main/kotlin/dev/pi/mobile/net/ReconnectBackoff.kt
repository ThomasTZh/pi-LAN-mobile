package dev.pi.mobile.net

/**
 * Reconnect policy for the single Pi socket: 0.5, 1, 2, 4 then 8 seconds, held
 * at 8 s. The caller keeps retrying; only a foreground service or an open app
 * is allowed to reconnect.
 */
object ReconnectBackoff {
    val STEPS_MS: List<Long> = listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L)
    const val MAX_STEP_MS = 8_000L

    /** Delay before retry number [attempt] (0-based). */
    fun delayFor(attempt: Int): Long = STEPS_MS[attempt.coerceIn(0, STEPS_MS.lastIndex)]

    fun label(attempt: Int): String = "${delayFor(attempt) / 1000.0}s"
}
