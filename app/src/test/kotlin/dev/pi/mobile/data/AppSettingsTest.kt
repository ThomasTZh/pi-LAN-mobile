package dev.pi.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Review F17: the Settings toggle reaches the notification builder through
 * `ChatController.setHideDetails -> AppSettings.withHideDetails`. The data class
 * transform is pure, so the wiring decision is pinned here; the Android
 * SharedPreferences write itself stays code-review (needs Robolectric).
 */
class AppSettingsTest {
    @Test
    fun withHideDetailsSetsOnlyThePrivacyFlag() {
        val base = AppSettings(
            alertsEnabled = true,
            theme = "light",
            textScale = 130,
            haptics = false,
        )
        val on = base.withHideDetails(true)
        assertTrue(on.hideDetails)
        assertEquals(base.copy(hideDetails = true), on)
        assertEquals("light", on.theme)
        assertEquals(130, on.textScale)
        assertFalse(on.haptics)
        // Off again, and the default stays off.
        assertFalse(on.withHideDetails(false).hideDetails)
        assertFalse(AppSettings().hideDetails)
    }
}
