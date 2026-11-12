package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.chat.WorkingIndicator
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** App-owned "Pi is working…" line. */
@RunWith(AndroidJUnit4::class)
class WorkingIndicatorTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun shows_while_working_and_carries_the_queue_count() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                WorkingIndicator(visible = true, queueCount = 2)
            }
        }
        compose.onNodeWithTag("pi_working").assertIsDisplayed()
        compose.onNodeWithText("Pi is working…").assertIsDisplayed()
        compose.onNodeWithText("2 queued").assertIsDisplayed()
    }

    @Test
    fun hides_when_no_run_is_active() {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                WorkingIndicator(visible = false, queueCount = 0)
            }
        }
        compose.onAllNodesWithTag("pi_working").assertCountEquals(0)
    }
}
