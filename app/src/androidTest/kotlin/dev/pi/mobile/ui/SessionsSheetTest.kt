package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pi.mobile.R
import dev.pi.mobile.session.SessionRow
import dev.pi.mobile.session.SessionsState
import dev.pi.mobile.ui.sheets.SessionsSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Sessions sheet: grouping, current row, open/rename/delete and new chat. */
@RunWith(AndroidJUnit4::class)
class SessionsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val switchTitle = context.getString(R.string.sheet_switch)
    private val renameTitle = context.getString(R.string.sheet_rename)
    private val renameAction = context.getString(R.string.sheet_rename_action)

    private fun setSessions(
        state: SessionsState = UiFixtures.sessionsState(),
        currentPath: String? = "/sessions/one.jsonl",
        streaming: Boolean = false,
        onNewChat: () -> Unit = {},
        onOpen: (SessionRow) -> Unit = {},
        onRename: (SessionRow, String) -> Unit = { _, _ -> },
        onDelete: (SessionRow) -> Unit = {},
        onRefresh: () -> Unit = {},
    ) {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                SessionsSheetContent(
                    state = state,
                    currentPath = currentPath,
                    streaming = streaming,
                    onNewChat = onNewChat,
                    onRefresh = onRefresh,
                    onOpen = onOpen,
                    onRename = onRename,
                    onDelete = onDelete,
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun lists_groups_and_session_rows() {
        setSessions()
        compose.onNodeWithText("localLLMsetup").assertIsDisplayed()
        compose.onNodeWithText("Pi Mobile plan").assertIsDisplayed()
        compose.onNodeWithText("Check the Gradle build").assertIsDisplayed()
        compose.onNodeWithText("Other project").assertIsDisplayed()
        compose.onNodeWithText("5m · 12 messages", substring = true).assertIsDisplayed()
    }

    @Test
    fun pull_to_refresh_requests_a_reload() {
        // Review F5 / PLAN 5.5: the gesture has to work in addition to the icon.
        var refreshes = 0
        setSessions(onRefresh = { refreshes++ })
        compose.onNodeWithTag("sessions_pull_refresh").assertIsDisplayed()
        compose.onNodeWithTag("sessions_pull_refresh").performTouchInput {
            swipeDown(startY = centerY, endY = centerY + 600f, durationMillis = 400)
        }
        compose.waitUntil(timeoutMillis = 5_000) { refreshes > 0 }
        assertTrue(refreshes > 0)
    }

    @Test
    fun new_chat_row_invokes_the_callback() {
        var newChat = 0
        setSessions(onNewChat = { newChat++ })
        compose.onNodeWithTag("sessions_new_chat").performClick()
        compose.runOnIdle { assertEquals(1, newChat) }
    }

    @Test
    fun tapping_a_row_opens_it() {
        var opened: SessionRow? = null
        setSessions(onOpen = { opened = it })
        compose.onNodeWithText("Pi Mobile plan").performClick()
        compose.runOnIdle { assertEquals("/sessions/one.jsonl", opened?.path) }
    }

    @Test
    fun opening_while_streaming_asks_first() {
        var opened: SessionRow? = null
        setSessions(streaming = true, onOpen = { opened = it })
        compose.onNodeWithText("Pi Mobile plan").performClick()
        compose.onNodeWithText(switchTitle).assertIsDisplayed()
        compose.onNodeWithText("Switch").performClick()
        compose.runOnIdle { assertEquals("/sessions/one.jsonl", opened?.path) }
    }

    @Test
    fun rename_flow_updates_the_session_name() {
        var renamed: Pair<String, String>? = null
        setSessions(onRename = { row, name -> renamed = row.path to name })
        compose.onAllNodesWithContentDescription("Session actions")[0].performClick()
        compose.onNodeWithText(renameTitle).performClick()
        compose.onNodeWithTag("rename_field").performTextClearance()
        compose.onNodeWithTag("rename_field").performTextInput("Renamed session")
        compose.onNodeWithText(renameAction).performClick()
        compose.runOnIdle { assertEquals("/sessions/one.jsonl" to "Renamed session", renamed) }
    }

    @Test
    fun delete_flow_asks_for_confirmation() {
        var deleted: String? = null
        setSessions(onDelete = { deleted = it.path })
        compose.onAllNodesWithContentDescription("Session actions")[0].performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNodeWithText("Delete session?").assertIsDisplayed()
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals("/sessions/one.jsonl", deleted) }
    }

    @Test
    fun empty_list_has_a_message() {
        setSessions(state = SessionsState())
        compose.onNodeWithText("No sessions on the desktop yet.").assertIsDisplayed()
    }

    @Test
    fun missing_folder_is_flagged() {
        val state = UiFixtures.sessionsState()
        setSessions(state = state)
        assertTrue(state.rows.any { !it.cwdExists })
        assertFalse(state.groups.isEmpty())
        compose.onNodeWithText("missing folder", substring = true).assertIsDisplayed()
    }
}
