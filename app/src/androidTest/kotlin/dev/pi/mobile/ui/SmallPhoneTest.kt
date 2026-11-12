package dev.pi.mobile.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.pi.mobile.ui.chat.ChatActions
import dev.pi.mobile.ui.chat.ChatScreen
import dev.pi.mobile.ui.sheets.ApprovalSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Small-phone rules from PLAN.md 5.5: a 360dp-wide wrapper and a 130% app text
 * size on top of a 1.3 system font scale. The measurements are logged so the
 * device run reports real pixel values, not just "it looked fine".
 */
@RunWith(AndroidJUnit4::class)
class SmallPhoneTest {

    @get:Rule
    val compose = createComposeRule()

    private val density get() = compose.density.density

    private fun width360(fontScale: Float = 1f, content: @Composable () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                PiTheme(theme = "dark", textScalePercent = 100) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .testTag("small_phone_root"),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    @Test
    fun chat_fits_a_360dp_phone() {
        width360 { ChatScreen(state = UiFixtures.richState(), actions = ChatActions()) }
        compose.onNodeWithTag("small_phone_root").assertIsDisplayed()
        compose.onNodeWithTag("chat_title").assertIsDisplayed()
        compose.onNodeWithTag("composer_input").assertIsDisplayed()
        compose.onNodeWithTag("composer_send").assertIsDisplayed()

        val root = compose.onNodeWithTag("small_phone_root").fetchSemanticsNode().size
        val send = compose.onNodeWithTag("composer_send").fetchSemanticsNode().boundsInRoot
        val bar = compose.onNodeWithTag("chat_title").fetchSemanticsNode().boundsInRoot
        Log.i(
            TAG,
            "360dp chat: root=${root.width}x${root.height}px (${root.width / density}dp wide), " +
                "send.right=${send.right}px, title.right=${bar.right}px",
        )
        assertEquals(with(compose.density) { 360.dp.roundToPx() }, root.width)
        assertTrue("composer send must stay inside 360dp", send.right <= 360f * density + 1f)
        assertTrue("title must stay inside 360dp", bar.right <= 360f * density + 1f)
    }

    @Test
    fun large_text_stays_reachable() {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 1.3f)) {
                PiTheme(theme = "dark", textScalePercent = 130) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(360.dp)
                                .fillMaxHeight()
                                .testTag("small_phone_root"),
                        ) {
                            ChatScreen(state = UiFixtures.markdownState(), actions = ChatActions())
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_title").assertIsDisplayed()
        compose.onNodeWithTag("composer_input").assertIsDisplayed()
        compose.onNodeWithTag("composer_send").assertIsDisplayed()
        val send = compose.onNodeWithTag("composer_send").fetchSemanticsNode().boundsInRoot
        Log.i(TAG, "130% text: send bounds=$send, density=$density, fontScale=1.3")
        assertTrue(send.right <= 360f * density + 1f)
        assertTrue(send.left >= 0f)
    }

    @Test
    fun approval_actions_fit_a_360dp_phone() {
        val dialog = UiFixtures.confirmDialog().copy(
            message = "rm -rf build && ./gradlew :app:assembleDebug && echo done with a long line to wrap",
        )
        width360 {
            ApprovalSheetContent(
                dialog = dialog,
                waitingCount = 1,
                onAnswer = { _, _, _ -> },
                onCancel = {},
                onTimeout = {},
            )
        }
        compose.onNodeWithTag("approval_allow").assertIsDisplayed()
        compose.onNodeWithTag("approval_deny").assertIsDisplayed()
        val allow = compose.onNodeWithTag("approval_allow").fetchSemanticsNode().boundsInRoot
        val deny = compose.onNodeWithTag("approval_deny").fetchSemanticsNode().boundsInRoot
        Log.i(TAG, "360dp approval: deny=$deny allow=$allow")
        assertTrue(allow.right <= 360f * density + 1f)
        assertTrue(deny.left >= 0f)
        assertTrue(allow.height / density >= 48f)
    }

    private companion object {
        const val TAG = "PiSmallPhone"
    }
}
