package dev.pi.mobile.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.session.PiCommand
import dev.pi.mobile.ui.chat.Composer
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Slash-command palette in the composer. */
@RunWith(AndroidJUnit4::class)
class ComposerPaletteTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setComposer(
        initial: String,
        commands: List<PiCommand> = listOf(
            PiCommand("plan", "Plan mode"),
            PiCommand("fix-tests", "Fix failing tests", "prompt"),
        ),
    ): MutableValue {
        val value = mutableStateOf(initial)
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                Composer(
                    value = value.value,
                    onValueChange = { value.value = it },
                    streaming = false,
                    enabled = true,
                    onSend = {},
                    onQueue = {},
                    onStop = {},
                    commands = commands,
                )
            }
        }
        return MutableValue(value)
    }

    /** Small holder so the test can read the state without leaking Compose types. */
    class MutableValue(private val state: androidx.compose.runtime.MutableState<String>) {
        val value: String get() = state.value
    }

    @Test
    fun slash_shows_suggestions_and_pick_inserts_the_command() {
        val value = setComposer("/")
        compose.onNodeWithTag("command_plan").assertIsDisplayed()
        compose.onNodeWithTag("command_plan").performClick()
        compose.waitForIdle()
        assertEquals("/plan ", value.value)
    }

    @Test
    fun prefix_filters_the_list() {
        setComposer("/fix")
        compose.onNodeWithTag("command_fix-tests").assertIsDisplayed()
        compose.onAllNodesWithTag("command_plan").assertCountEquals(0)
    }

    @Test
    fun palette_hides_once_an_argument_starts() {
        setComposer("/plan x")
        compose.onAllNodesWithTag("command_plan").assertCountEquals(0)
    }
}
