package dev.pi.mobile.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Review F1: the notification privacy mode has to be reachable and provable.
 * The decision lives in [PiNotifications.alertContent] so it can be tested on
 * the JVM; the builder uses exactly these values.
 */
class PiNotificationsPrivacyTest {
    private val genericTitle = "Pi Mobile"
    private val genericBody = "Open the app to see the update."

    private fun content(title: String, body: String, hideDetails: Boolean) = PiNotifications.alertContent(
        title = title,
        body = body,
        hideDetails = hideDetails,
        genericTitle = genericTitle,
        genericBody = genericBody,
    )

    @Test
    fun detailsOnKeepsTheAlertTextButStaysLockScreenPrivate() {
        val content = content("Pi needs approval", "write /home/user/notes.txt", hideDetails = false)
        assertEquals("Pi needs approval", content.title)
        assertEquals("write /home/user/notes.txt", content.body)
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_PRIVATE, content.visibility)
    }

    @Test
    fun hideDetailsReplacesTextAndAsksForSecretVisibility() {
        val content = content("Pi needs approval", "write /home/user/notes.txt", hideDetails = true)
        assertEquals(genericTitle, content.title)
        assertEquals(genericBody, content.body)
        assertEquals(androidx.core.app.NotificationCompat.VISIBILITY_SECRET, content.visibility)
        assertNotEquals("write /home/user/notes.txt", content.body)
    }

    @Test
    fun thePublicVersionIsAlwaysGeneric() {
        val public = PiNotifications.publicContent(genericTitle, genericBody)
        assertEquals(genericTitle, public.title)
        assertEquals(genericBody, public.body)
        // ... and it differs from the real alert in both privacy modes.
        val visible = content("Pi needs approval", "write /home/user/notes.txt", hideDetails = false)
        assertNotEquals(visible.title, public.title)
        assertNotEquals(visible.body, public.body)
    }
}
