package dev.pi.mobile.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Time formatting shared by the sessions sheet, tool cards and chat metadata. */
object TimeFormat {
    private val SAME_DAY = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val OTHER_DAY = DateTimeFormatter.ofPattern("d MMM", Locale.US)

    /** Compact relative time: "just now", "5m", "3h", "2d", then an absolute date. */
    fun relative(thenMs: Long, nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (thenMs <= 0L) return "unknown"
        val delta = nowMs - thenMs
        if (delta < 0) return "just now"
        val minutes = delta / 60_000L
        if (minutes < 1) return "just now"
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return "${hours}h"
        val days = hours / 24
        if (hours < 24 * 6) return "${days}d"
        val instant = Instant.ofEpochMilli(thenMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        return if (instant.toLocalDate() == now.toLocalDate()) {
            SAME_DAY.format(instant)
        } else {
            OTHER_DAY.format(instant)
        }
    }

    /** "0.4s", "3.2s", "1m 04s". Used for thinking blocks and tool durations. */
    fun duration(ms: Long): String {
        if (ms <= 0L) return "0s"
        if (ms < 1_000L) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        if (ms < 60_000L) return String.format(Locale.US, "%.1fs", ms / 1000.0)
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%dm %02ds", minutes, seconds)
    }

    fun clock(thenMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        SAME_DAY.format(Instant.ofEpochMilli(thenMs).atZone(zone))
}
