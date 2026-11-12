package dev.pi.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pi.mobile.R
import dev.pi.mobile.session.ChatItem
import dev.pi.mobile.session.DeliveryState
import dev.pi.mobile.ui.chat.TAG_TICK_PROCESSING
import dev.pi.mobile.ui.chat.TAG_TICK_SENT
import dev.pi.mobile.ui.chat.UserMessageRow
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Delivery ticks on the user bubble: nothing while Sending, one drawn check for
 * Sent, two for Processing. Each state also carries a TalkBack description.
 */
@RunWith(AndroidJUnit4::class)
class DeliveryTickTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun show(delivery: DeliveryState) {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                UserMessageRow(
                    item = ChatItem.UserMessage(id = "u1", text = "Run the tests", delivery = delivery),
                    onCopy = {},
                )
            }
        }
    }

    @Test
    fun sending_shows_no_tick() {
        show(DeliveryState.Sending)
        compose.onAllNodesWithTag(TAG_TICK_SENT, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag(TAG_TICK_PROCESSING, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun sent_shows_one_check() {
        show(DeliveryState.Sent)
        compose.onNodeWithTag(TAG_TICK_SENT, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag(TAG_TICK_PROCESSING, useUnmergedTree = true).assertCountEquals(0)
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.chat_tick_sent),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }

    @Test
    fun processing_shows_two_checks() {
        show(DeliveryState.Processing)
        compose.onNodeWithTag(TAG_TICK_PROCESSING, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag(TAG_TICK_SENT, useUnmergedTree = true).assertCountEquals(0)
        compose
            .onNodeWithContentDescription(
                context.getString(R.string.chat_tick_processing),
                useUnmergedTree = true,
            )
            .assertIsDisplayed()
    }
}
