package dev.pi.mobile.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ReconnectBackoffTest {
    @Test
    fun followsTheDocumentedSchedule() {
        assertEquals(500L, ReconnectBackoff.delayFor(0))
        assertEquals(1_000L, ReconnectBackoff.delayFor(1))
        assertEquals(2_000L, ReconnectBackoff.delayFor(2))
        assertEquals(4_000L, ReconnectBackoff.delayFor(3))
        assertEquals(8_000L, ReconnectBackoff.delayFor(4))
    }

    @Test
    fun holdsEightSecondsAfterTheSchedule() {
        assertEquals(8_000L, ReconnectBackoff.delayFor(5))
        assertEquals(8_000L, ReconnectBackoff.delayFor(50))
        assertEquals(8_000L, ReconnectBackoff.MAX_STEP_MS)
    }

    @Test
    fun negativeAttemptsUseTheFirstStep() {
        assertEquals(500L, ReconnectBackoff.delayFor(-3))
        assertEquals("0.5s", ReconnectBackoff.label(0))
    }
}
