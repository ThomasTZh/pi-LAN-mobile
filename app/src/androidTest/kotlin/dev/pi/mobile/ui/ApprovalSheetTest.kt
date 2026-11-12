package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.session.PendingDialog
import dev.pi.mobile.ui.sheets.ApprovalSheetContent
import dev.pi.mobile.ui.sheets.looksLikeCommand
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Approval sheet: confirm/select/input actions, waiting count and timeout. */
@RunWith(AndroidJUnit4::class)
class ApprovalSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private data class Answer(val id: String, val confirmed: Boolean?, val value: String?)

    private fun setApproval(
        dialog: PendingDialog,
        waitingCount: Int = 0,
        onAnswer: (Answer) -> Unit = {},
        onCancel: (String) -> Unit = {},
        onTimeout: (String) -> Unit = {},
    ) {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ApprovalSheetContent(
                    dialog = dialog,
                    waitingCount = waitingCount,
                    onAnswer = { id, confirmed, value -> onAnswer(Answer(id, confirmed, value)) },
                    onCancel = onCancel,
                    onTimeout = onTimeout,
                )
            }
        }
    }

    @Test
    fun confirm_deny_and_allow_report_the_decision() {
        val answers = mutableListOf<Answer>()
        setApproval(UiFixtures.confirmDialog(), onAnswer = { answers += it })
        compose.onNodeWithText("Run this command?").assertIsDisplayed()
        compose.onNodeWithTag("approval_deny").performClick()
        compose.onNodeWithTag("approval_allow").performClick()
        compose.runOnIdle {
            assertEquals(2, answers.size)
            assertEquals("d1", answers[0].id)
            assertEquals(false, answers[0].confirmed)
            assertEquals(true, answers[1].confirmed)
        }
    }

    @Test
    fun confirm_targets_are_at_least_48dp() {
        setApproval(UiFixtures.confirmDialog())
        val deny = compose.onNodeWithTag("approval_deny").fetchSemanticsNode().size
        val allow = compose.onNodeWithTag("approval_allow").fetchSemanticsNode().size
        val density = compose.density.density
        assertTrue((deny.height / density) >= 48f)
        assertTrue((allow.height / density) >= 48f)
    }

    @Test
    fun waiting_count_is_shown() {
        setApproval(UiFixtures.confirmDialog(), waitingCount = 2)
        compose.onNodeWithTag("approval_waiting").assertIsDisplayed()
        compose.onNodeWithText("2 more waiting", substring = true).assertIsDisplayed()
    }

    @Test
    fun timeout_countdown_is_visible() {
        setApproval(UiFixtures.confirmDialog(timeoutMs = 30_000))
        compose.onNodeWithTag("approval_countdown").assertIsDisplayed()
        compose.onNodeWithText("Auto-dismiss in", substring = true).assertIsDisplayed()
    }

    @Test
    fun an_expired_countdown_only_drops_the_sheet_locally() {
        // Review F8: Pi resolves a timed-out dialog itself, so the client must
        // not answer. The countdown is display-only and reports the deadline.
        var timedOut: String? = null
        var cancelled: String? = null
        val answers = mutableListOf<Answer>()
        setApproval(
            UiFixtures.confirmDialog(timeoutMs = 1),
            onAnswer = { answers += it },
            onCancel = { cancelled = it },
            onTimeout = { timedOut = it },
        )
        compose.waitUntil(timeoutMillis = 5_000) { timedOut != null }
        compose.runOnIdle {
            assertEquals("d1", timedOut)
            assertTrue("a timeout must not answer the dialog", answers.isEmpty())
            assertTrue("a timeout must not send cancelled", cancelled == null)
        }
    }

    @Test
    fun select_option_row_answers_with_its_value() {
        val answers = mutableListOf<Answer>()
        setApproval(UiFixtures.selectDialog(), onAnswer = { answers += it })
        compose.onNodeWithText("Qwen3 Small").assertIsDisplayed()
        compose.onNodeWithTag("approval_option_1").performClick()
        compose.runOnIdle {
            assertEquals("d2", answers.single().id)
            assertEquals("Qwen3 Small", answers.single().value)
        }
    }

    @Test
    fun input_submits_typed_value_and_disables_empty_submit() {
        val answers = mutableListOf<Answer>()
        setApproval(UiFixtures.inputDialog(), onAnswer = { answers += it })
        compose.onNodeWithTag("approval_submit").assertIsNotEnabled()
        compose.onNodeWithTag("approval_input").performTextInput("my session")
        compose.onNodeWithTag("approval_submit").assertIsEnabled()
        compose.onNodeWithTag("approval_submit").performClick()
        compose.runOnIdle { assertEquals("my session", answers.single().value) }
    }

    @Test
    fun editor_starts_with_prefill_and_submits_it() {
        val answers = mutableListOf<Answer>()
        setApproval(UiFixtures.editorDialog(), onAnswer = { answers += it })
        compose.onNodeWithTag("approval_input").performTextClearance()
        compose.onNodeWithTag("approval_input").performTextInput("edited")
        compose.onNodeWithTag("approval_submit").performClick()
        compose.runOnIdle { assertEquals("edited", answers.single().value) }
    }

    @Test
    fun cancel_reports_cancelled_for_the_dialog() {
        var cancelled: String? = null
        setApproval(UiFixtures.selectDialog(), onCancel = { cancelled = it })
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals("d2", cancelled) }
    }

    @Test
    fun command_like_messages_use_the_monospace_heuristic() {
        assertTrue(looksLikeCommand("rm -rf build && ./gradlew assembleDebug"))
        assertTrue(looksLikeCommand("git status"))
        assertTrue(looksLikeCommand("line one\nline two"))
        assertFalse(looksLikeCommand("This is a normal explanation of the change."))
        assertFalse(looksLikeCommand(""))
    }
}
