package dev.pi.mobile.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pi.mobile.R
import dev.pi.mobile.data.AppSettings
import dev.pi.mobile.ui.sheets.SettingsSheetContent
import dev.pi.mobile.ui.theme.PiTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Settings sheet: alerts/permission, theme, text size, keep awake, forget. */
@RunWith(AndroidJUnit4::class)
class SettingsSheetTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun setSettings(
        settings: AppSettings = AppSettings(alertsEnabled = true),
        notificationsAllowed: Boolean = false,
        onAlertsChange: (Boolean) -> Unit = {},
        onHideDetailsChange: (Boolean) -> Unit = {},
        onThemeChange: (String) -> Unit = {},
        onTextScaleChange: (Int) -> Unit = {},
        batteryOptimised: Boolean = true,
        onBatteryOptimisation: () -> Unit = {},
        onForget: () -> Unit = {},
    ) {
        compose.setContent {
            PiTheme(theme = settings.theme, textScalePercent = settings.textScale) {
                SettingsSheetContent(
                    settings = settings,
                    notificationsAllowed = notificationsAllowed,
                    onAlertsChange = onAlertsChange,
                    onRequestNotifications = {},
                    onHideDetailsChange = onHideDetailsChange,
                    onThemeChange = onThemeChange,
                    onTextScaleChange = onTextScaleChange,
                    onKeepAwakeChange = {},
                    onHapticsChange = {},
                    batteryOptimised = batteryOptimised,
                    onBatteryOptimisation = onBatteryOptimisation,
                    onForget = onForget,
                    onClose = {},
                )
            }
        }
    }

    @Test
    fun shows_permission_state_and_allow_action() {
        setSettings(notificationsAllowed = false)
        compose.onNodeWithText(context.getString(R.string.settings_notifications_denied)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_allow)).assertIsDisplayed()
    }

    @Test
    fun theme_and_text_size_choices_report_values() {
        var theme: String? = null
        var scale: Int? = null
        setSettings(onThemeChange = { theme = it }, onTextScaleChange = { scale = it })
        compose.onNodeWithText("Light").performClick()
        compose.onNodeWithText("130%").performClick()
        compose.runOnIdle {
            assertEquals("light", theme)
            assertEquals(130, scale)
        }
    }

    @Test
    fun keep_awake_and_haptics_rows_are_present() {
        setSettings()
        compose.onNodeWithText(context.getString(R.string.settings_keep_awake)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_haptics)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.settings_about_license)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun toggle_rows_are_at_least_48dp() {
        setSettings()
        compose.onNodeWithText(context.getString(R.string.settings_keep_awake)).performScrollTo()
        val density = compose.density.density
        val row = compose.onNodeWithText(context.getString(R.string.settings_keep_awake))
            .fetchSemanticsNode().size.height / density
        org.junit.Assert.assertTrue("settings row is ${row}dp", row >= 48f)
    }

    @Test
    fun hide_notification_details_toggle_is_reachable_and_reports_changes() {
        // Review F1: the G7 privacy mode needs a control, not just a builder.
        var hidden: Boolean? = null
        setSettings(onHideDetailsChange = { hidden = it })
        compose.onNodeWithText(context.getString(R.string.settings_hide_details))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_hide_details").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(true, hidden) }
    }

    @Test
    fun battery_optimisation_row_shows_the_state_and_opens_the_system_screen() {
        // Review F9 / PLAN 5.6.
        var opened = 0
        setSettings(batteryOptimised = true, onBatteryOptimisation = { opened++ })
        compose.onNodeWithText(context.getString(R.string.settings_battery_optimised))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("settings_battery").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun battery_optimisation_row_reports_an_existing_exemption() {
        setSettings(batteryOptimised = false)
        compose.onNodeWithText(context.getString(R.string.settings_battery_ignored))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun forget_requires_confirmation() {
        var forgotten = 0
        setSettings(onForget = { forgotten++ })
        compose.onNodeWithTag("settings_forget").performScrollTo().performClick()
        compose.onNodeWithText(context.getString(R.string.chat_forget_title)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.chat_forget)).performClick()
        compose.runOnIdle { assertEquals(1, forgotten) }
    }
}
