package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.pi.mobile.ui.connect.ConnectScreen
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Connect screen: prefill, validation, inline errors and the Remember toggle. */
@RunWith(AndroidJUnit4::class)
class ConnectScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private fun setConnect(
        initialAddress: String = "192.168.1.50:7690",
        remember: Boolean = true,
        connecting: Boolean = false,
        error: String? = null,
        onRememberChange: (Boolean) -> Unit = {},
        onConnect: (String, String) -> Unit = { _, _ -> },
    ) {
        compose.setContent {
            PiTheme(theme = "dark", textScalePercent = 100) {
                ConnectScreen(
                    initialAddress = initialAddress,
                    remember = remember,
                    connecting = connecting,
                    error = error,
                    onRememberChange = onRememberChange,
                    onConnect = onConnect,
                )
            }
        }
    }

    @Test
    fun shows_default_address_and_remember_on() {
        setConnect()
        compose.onNodeWithTag("connect_address").assertTextContains("192.168.1.50:7690")
        compose.onNodeWithTag("connect_remember").assertIsOn()
        compose.onNodeWithText("Pi Mobile").assertIsDisplayed()
        compose.onNodeWithText("Start the Pi host on your computer, then enter the address and PIN it prints.").assertIsDisplayed()
    }

    @Test
    fun pin_field_is_masked_by_default_and_the_toggle_reveals_it() {
        // Review F16: a PIN that grants code execution on the desktop must not
        // render in clear text unless the user asks for it.
        setConnect()
        // The reveal control starts in the "Show PIN" state, i.e. the field is
        // masked, and no "Hide PIN" control exists yet.
        compose.onNodeWithContentDescription("Show PIN").assertIsDisplayed()
        compose.onAllNodesWithText("Hide PIN").assertCountEquals(0)
        compose.onNodeWithTag("connect_pin").performTextInput("123456")
        compose.onNodeWithTag("connect_pin").assertTextContains("123 456")
        // Reveal: the control flips and the value is unchanged.
        compose.onNodeWithContentDescription("Show PIN").performClick()
        compose.onNodeWithContentDescription("Hide PIN").assertIsDisplayed()
        compose.onNodeWithTag("connect_pin").assertTextContains("123 456")
        // Hide again: back to the masked state, same value.
        compose.onNodeWithContentDescription("Hide PIN").performClick()
        compose.onNodeWithContentDescription("Show PIN").assertIsDisplayed()
        compose.onNodeWithTag("connect_pin").assertTextContains("123 456")
    }

    @Test
    fun remember_toggle_reports_change() {
        var value: Boolean? = null
        setConnect(onRememberChange = { value = it })
        compose.onNodeWithTag("connect_remember").performClick()
        compose.runOnIdle { assertEquals(false, value) }
    }

    @Test
    fun rejects_public_address_inline() {
        setConnect()
        compose.onNodeWithTag("connect_address").performTextClearance()
        compose.onNodeWithTag("connect_address").performTextInput("8.8.8.8:7690")
        compose.onNodeWithTag("connect_pin").performTextInput("123456")
        compose.onNodeWithTag("connect_submit").performClick()
        compose.onNodeWithTag("connect_error").assertIsDisplayed()
        compose.onNodeWithText("private LAN address", substring = true).assertIsDisplayed()
    }

    @Test
    fun rejects_short_pin_inline() {
        setConnect()
        compose.onNodeWithTag("connect_pin").performTextInput("12345")
        compose.onNodeWithTag("connect_submit").performClick()
        compose.onNodeWithText("six digits", substring = true).assertIsDisplayed()
    }

    @Test
    fun pin_is_shown_in_groups_of_three() {
        setConnect()
        compose.onNodeWithTag("connect_pin").performTextInput("123456")
        compose.onNodeWithText("123 456").assertIsDisplayed()
    }

    @Test
    fun connect_callback_gets_address_and_digits() {
        var address: String? = null
        var pin: String? = null
        setConnect(onConnect = { a, p ->
            address = a
            pin = p
        })
        compose.onNodeWithTag("connect_address").performTextClearance()
        compose.onNodeWithTag("connect_address").performTextInput("10.0.0.5:7690")
        compose.onNodeWithTag("connect_pin").performTextInput("123456")
        compose.onNodeWithTag("connect_submit").performClick()
        compose.runOnIdle {
            assertEquals("10.0.0.5:7690", address)
            assertEquals("123456", pin)
        }
    }

    @Test
    fun connecting_state_disables_the_button_and_spins() {
        setConnect(connecting = true)
        compose.onNodeWithTag("connect_submit").assertIsNotEnabled()
        compose.onNodeWithText("Connecting").assertIsDisplayed()
    }

    @Test
    fun rejected_pin_maps_to_the_inline_rejected_state() {
        setConnect(error = "That PIN was not accepted.")
        compose.onNodeWithText("PIN rejected", substring = true).assertIsDisplayed()
    }

    @Test
    fun locked_pin_maps_to_the_lockout_state() {
        setConnect(error = "PIN locked, wait 30 s.")
        compose.onNodeWithText("PIN locked", substring = true).assertIsDisplayed()
    }

    @Test
    fun unreachable_host_maps_to_the_unreachable_state() {
        setConnect(error = "The host refused the connection. Is the Pi host running?")
        compose.onNodeWithText("Host unreachable", substring = true).assertIsDisplayed()
    }

    @Test
    fun websocket_failure_maps_to_not_a_pi_host() {
        setConnect(error = "The desktop did not answer the WebSocket handshake.")
        compose.onNodeWithText("Not a Pi host", substring = true).assertIsDisplayed()
    }

    @Test
    fun idle_button_is_enabled_and_has_no_spinner_text() {
        setConnect(connecting = false)
        compose.onNodeWithTag("connect_submit").assertIsEnabled()
        assertTrue(compose.onAllNodesWithText("Connecting").fetchSemanticsNodes().isEmpty())
    }
}
