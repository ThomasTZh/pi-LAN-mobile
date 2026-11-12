package dev.pi.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.chat.ChatActions
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PLAN.md 5.5: below 600dp the app is one column with sheets; at or above it the
 * sessions list becomes a fixed left rail. The phone is 393dp, so this test wraps
 * the real [WideChatLayout] in a 700dp-wide window to assert the rail rule.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    private val density get() = compose.density.density

    @Test
    fun wide_layout_keeps_a_340dp_sessions_rail_next_to_chat() {
        // Half density maps the 700dp virtual window onto the 393dp phone, so the
        // whole two-pane layout is on-screen and its geometry is measurable.
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density / 2f, base.fontScale)) {
                PiTheme(theme = "dark", textScalePercent = 100) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(700.dp)
                                .fillMaxHeight()
                                .testTag("wide_root"),
                        ) {
                            WideChatLayout(
                                state = UiFixtures.markdownState(),
                                sessions = UiFixtures.sessionsState(),
                                actions = ChatActions(),
                                approvalWaiting = false,
                                waitingCount = 0,
                                onOpenApproval = {},
                                onNewChat = {},
                                onRefreshSessions = {},
                                onOpenSession = {},
                                onRenameSession = { _, _ -> },
                                onDeleteSession = {},
                            )
                        }
                    }
                }
            }
        }
        compose.onNodeWithTag("wide_root").assertIsDisplayed()
        compose.onNodeWithTag("sessions_sheet").assertIsDisplayed()
        compose.onNodeWithTag("chat_title").assertIsDisplayed()
        compose.onNodeWithTag("composer_input").assertIsDisplayed()

        val virtualDensity = density / 2f
        val rail = compose.onNodeWithTag("sessions_sheet").fetchSemanticsNode().boundsInRoot
        val composer = compose.onNodeWithTag("composer_input").fetchSemanticsNode().boundsInRoot
        assertEquals(340f, rail.width / virtualDensity, 2f)
        assertTrue(
            "composer (${composer.left / density}dp) must start right of the rail " +
                "(${rail.right / density}dp)",
            composer.left >= rail.right,
        )
    }
}
