package dev.pi.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {
    private val now = 1_760_000_000_000L

    @Test
    fun relativeMinutesHoursDays() {
        assertEquals("just now", TimeFormat.relative(now - 5_000, now))
        assertEquals("5m", TimeFormat.relative(now - 5 * 60_000, now))
        assertEquals("3h", TimeFormat.relative(now - 3 * 3_600_000, now))
        assertEquals("2d", TimeFormat.relative(now - 2 * 86_400_000, now))
        assertEquals("unknown", TimeFormat.relative(0, now))
    }

    @Test
    fun relativeFallsBackToCalendarDate() {
        val value = TimeFormat.relative(now - 20 * 86_400_000, now)
        org.junit.Assert.assertTrue("expected a date, got $value", value.isNotBlank() && value != "20d")
    }

    @Test
    fun durations() {
        assertEquals("0s", TimeFormat.duration(0))
        assertEquals("0.4s", TimeFormat.duration(400))
        assertEquals("3.2s", TimeFormat.duration(3_200))
        assertEquals("1m 04s", TimeFormat.duration(64_000))
    }
}
