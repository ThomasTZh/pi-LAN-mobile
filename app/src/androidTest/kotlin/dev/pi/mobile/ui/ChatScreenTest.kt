package dev.pi.mobile.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.session.ChatState
import dev.pi.mobile.ui.chat.ChatActions
import dev.pi.mobile.ui.chat.ChatScreen
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Chat rendering from fixture states, thinking/tool expansion, banners, composer. */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setChat(
        state: ChatState = UiFixtures.markdownState(),
        actions: ChatActions = ChatActions(),
        approvalWaiting: Boolean = false,
        waitingCount: Int = 0,
    ) {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ChatScreen(
                    state = state,
                    actions = actions,
                    approvalWaiting = approvalWaiting,
                    waitingCount = waitingCount,
                )
            }
        }
    }

    @Test
    fun renders_user_bubble_and_markdown_blocks() {
        setChat()
        compose.onNodeWithText("Make the sessions sheet match the plan.").assertIsDisplayed()
        compose.onNodeWithText("Sessions sheet").assertIsDisplayed()
        compose.onNodeWithText("tested parser", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_list").performScrollToNode(hasText("first item"))
        compose.onNodeWithText("first item").assertIsDisplayed()
        compose.onNodeWithTag("chat_list").performScrollToNode(hasText("val answer = 42"))
        compose.onNodeWithText("val answer = 42").assertIsDisplayed()
        compose.onNodeWithText("kotlin").assertIsDisplayed()
        compose.onNodeWithTag("chat_list").performScrollToNode(hasText("quoted note"))
        compose.onNodeWithText("quoted note").assertIsDisplayed()
    }

    @Test
    fun top_bar_shows_title_model_and_session_icon() {
        setChat()
        compose.onNodeWithText("Pi Mobile plan").assertIsDisplayed()
        compose.onNodeWithText("Swift 1.5 · thinking: medium", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("chat_sessions").assertIsDisplayed()
        compose.onNodeWithTag("chat_menu").assertIsDisplayed()
    }

    @Test
    fun thinking_is_collapsed_until_tapped() {
        setChat()
        compose.onNodeWithText("Thinking · 3.2s").assertIsDisplayed()
        compose.onAllNodesWithText("The plan asks for 64dp rows", substring = true)
            .assertCountEquals(0)
        compose.onNodeWithText("Thinking · 3.2s").performClick()
        compose.onNodeWithText("The plan asks for 64dp rows", substring = true).assertIsDisplayed()
    }

    @Test
    fun tool_card_shows_name_summary_duration_and_expands() {
        setChat(state = UiFixtures.toolState(), actions = ChatActions())
        compose.onNodeWithText("read_file").assertIsDisplayed()
        compose.onNodeWithText("pi-app/PLAN.md", substring = true).assertIsDisplayed()
        compose.onNodeWithText("1.5s").assertIsDisplayed()
        compose.onNodeWithText("read_file").performClick()
        compose.onNodeWithText("line 1", substring = true).assertIsDisplayed()
    }

    @Test
    fun truncated_tool_output_is_marked() {
        setChat(state = UiFixtures.toolState(truncated = true))
        compose.onNodeWithText("read_file").performClick()
        compose.onNodeWithText("Output · truncated").assertIsDisplayed()
    }

    @Test
    fun system_notice_is_rendered() {
        setChat(state = UiFixtures.noticeState())
        compose.onNodeWithText("Context compacted").assertIsDisplayed()
    }

    @Test
    fun empty_state_shows_the_hero_and_disabled_send() {
        setChat(state = UiFixtures.emptyState())
        compose.onNodeWithText("Ask Pi anything").assertIsDisplayed()
        compose.onNodeWithTag("composer_send").assertIsNotEnabled()
    }

    @Test
    fun connecting_state_shows_loading_history() {
        setChat(state = UiFixtures.connectingState())
        compose.onNodeWithText("Loading session…").assertIsDisplayed()
        compose.onNodeWithText("Connecting…").assertIsDisplayed()
    }

    @Test
    fun error_state_offers_retry() {
        var retried = 0
        setChat(state = UiFixtures.failedState(), actions = ChatActions(onRetry = { retried++ }))
        compose.onNodeWithText("Host unreachable. Check the desktop.").assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retried) }
    }

    @Test
    fun run_interrupted_banner_is_visible() {
        setChat(state = UiFixtures.markdownState().copy(banner = "Run interrupted"))
        compose.onNodeWithTag("chat_banner").assertIsDisplayed()
        compose.onNodeWithText("Run interrupted").assertIsDisplayed()
    }

    @Test
    fun approval_waiting_banner_opens_the_sheet() {
        var opened = 0
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ChatScreen(
                    state = UiFixtures.markdownState(),
                    actions = ChatActions(),
                    approvalWaiting = true,
                    waitingCount = 1,
                    onOpenApproval = { opened++ },
                )
            }
        }
        compose.onNodeWithTag("approval_banner").assertIsDisplayed()
        compose.onNodeWithText("Approval waiting", substring = true).assertIsDisplayed()
        compose.onNodeWithTag("approval_banner").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun thinking_toggle_is_at_least_48dp() {
        setChat()
        val density = compose.density.density
        val row = compose.onNodeWithText("Thinking · 3.2s").fetchSemanticsNode().size.height / density
        assertTrue("thinking row is ${row}dp", row >= 48f)
    }

    @Test
    fun tool_card_row_is_at_least_48dp() {
        setChat(state = UiFixtures.toolState())
        val density = compose.density.density
        val row = compose.onNodeWithText("read_file").fetchSemanticsNode().size.height / density
        assertTrue("tool card row is ${row}dp", row >= 48f)
    }

    @Test
    fun jump_to_latest_appears_after_scrolling_up() {
        val items = (1..40).map { index ->
            dev.pi.mobile.session.ChatItem.Notice("n$index", "notice $index")
        }
        setChat(state = UiFixtures.baseState().copy(items = items))
        compose.onNodeWithTag("chat_list").performScrollToIndex(0)
        compose.onNodeWithTag("jump_to_latest").assertIsDisplayed()
        compose.onNodeWithTag("jump_to_latest").performClick()
    }

    @Test
    fun idle_composer_sends_and_clears() {
        var sent: String? = null
        setChat(actions = ChatActions(onSend = { sent = it }))
        compose.onNodeWithTag("composer_send").assertIsNotEnabled()
        compose.onNodeWithTag("composer_input").performClick()
        compose.onNodeWithTag("composer_input").performTextInput("Hello Pi")
        compose.onNodeWithTag("composer_send").assertIsEnabled()
        compose.onNodeWithTag("composer_send").performClick()
        compose.runOnIdle { assertEquals("Hello Pi", sent) }
        compose.onNodeWithTag("composer_send").assertIsNotEnabled()
    }

    @Test
    fun streaming_composer_queues_and_stops() {
        var queued: String? = null
        var stopped = 0
        setChat(
            state = UiFixtures.streamingState(),
            actions = ChatActions(onQueue = { queued = it }, onStop = { stopped++ }),
        )
        compose.onNodeWithTag("composer_send").assertDoesNotExist()
        compose.onNodeWithTag("composer_queue").assertIsNotEnabled()
        compose.onNodeWithTag("composer_input").performClick()
        compose.onNodeWithTag("composer_input").performTextInput("Please also do this")
        compose.onNodeWithTag("composer_queue").assertIsEnabled()
        compose.onNodeWithTag("composer_queue").performClick()
        compose.runOnIdle { assertEquals("Please also do this", queued) }
        compose.onNodeWithTag("composer_stop").performClick()
        compose.runOnIdle { assertEquals(1, stopped) }
    }

    @Test
    fun menu_opens_sheets_and_disconnects() {
        var opened = ""
        setChat(
            actions = ChatActions(
                onModel = { opened = "model" },
                onThinking = { opened = "thinking" },
                onStats = { opened = "stats" },
                onSettings = { opened = "settings" },
                onDisconnect = { opened = "disconnect" },
            ),
        )
        compose.onNodeWithTag("chat_menu").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle { assertEquals("settings", opened) }
        compose.onNodeWithTag("chat_menu").performClick()
        compose.onNodeWithText("Disconnect").performClick()
        compose.runOnIdle { assertEquals("disconnect", opened) }
    }
}
