package dev.pi.mobile.ui.sheets

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pi.mobile.R
import dev.pi.mobile.data.AppSettings
import dev.pi.mobile.ui.components.PiChoiceChips
import dev.pi.mobile.ui.components.PiDivider
import dev.pi.mobile.ui.components.PiSectionLabel
import dev.pi.mobile.ui.components.PiSettingRow
import dev.pi.mobile.ui.components.PiToggleRow

/**
 * Settings sheet (PLAN.md 5.5): alert toggle with the notification permission
 * state, theme, text size (100/115/130%), keep awake, haptics, forget server,
 * and the about/MIT notice.
 */
@Composable
fun SettingsSheetContent(
    settings: AppSettings,
    notificationsAllowed: Boolean,
    onAlertsChange: (Boolean) -> Unit,
    onRequestNotifications: () -> Unit,
    onHideDetailsChange: (Boolean) -> Unit,
    onThemeChange: (String) -> Unit,
    onTextScaleChange: (Int) -> Unit,
    onKeepAwakeChange: (Boolean) -> Unit,
    onHapticsChange: (Boolean) -> Unit,
    batteryOptimised: Boolean,
    onBatteryOptimisation: () -> Unit,
    onForget: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmForget by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .testTag("settings_sheet"),
    ) {
        SheetHeader(title = stringResource(R.string.sheet_settings), subtitle = null, onClose = onClose)

        PiSectionLabel(stringResource(R.string.settings_notifications))
        PiToggleRow(
            title = stringResource(R.string.settings_alerts),
            checked = settings.alertsEnabled,
            onCheckedChange = onAlertsChange,
            supporting = stringResource(if (notificationsAllowed) R.string.settings_notifications_allowed else R.string.settings_notifications_denied),
        )
        if (settings.alertsEnabled && !notificationsAllowed) {
            PiSettingRow(
                title = stringResource(R.string.settings_permission),
                supporting = stringResource(R.string.settings_permission_body),
                trailing = {
                    TextButton(onClick = onRequestNotifications) { Text(stringResource(R.string.settings_allow)) }
                },
            )
        }
        PiToggleRow(
            title = stringResource(R.string.settings_hide_details),
            checked = settings.hideDetails,
            onCheckedChange = onHideDetailsChange,
            supporting = stringResource(R.string.settings_hide_details_body),
            modifier = Modifier.testTag("settings_hide_details"),
        )

        PiDivider(Modifier.padding(vertical = 4.dp))

        PiSectionLabel(stringResource(R.string.settings_appearance))
        Text(
            text = stringResource(R.string.settings_theme),
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        PiChoiceChips(
            options = listOf(
                "system" to stringResource(R.string.theme_system),
                "dark" to stringResource(R.string.theme_dark),
                "light" to stringResource(R.string.theme_light),
            ),
            selected = settings.theme,
            onSelect = onThemeChange,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.settings_text_size),
            modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 6.dp),
            style = MaterialTheme.typography.bodyLarge,
        )
        PiChoiceChips(
            options = listOf(
                "100" to "100%",
                "115" to "115%",
                "130" to "130%",
            ),
            selected = settings.textScale.toString(),
            onSelect = { value -> value.toIntOrNull()?.let(onTextScaleChange) },
        )

        PiDivider(Modifier.padding(vertical = 4.dp))

        PiSectionLabel(stringResource(R.string.settings_device))
        PiToggleRow(
            title = stringResource(R.string.settings_keep_awake),
            checked = settings.keepAwake,
            onCheckedChange = onKeepAwakeChange,
        )
        PiToggleRow(
            title = stringResource(R.string.settings_haptics),
            checked = settings.haptics,
            onCheckedChange = onHapticsChange,
        )
        // PLAN 5.6: keep the foreground service alive on aggressive OEM battery
        // managers by pointing the user at the system exemption screen.
        PiSettingRow(
            title = stringResource(R.string.settings_battery),
            supporting = stringResource(
                if (batteryOptimised) R.string.settings_battery_optimised else R.string.settings_battery_ignored,
            ),
            modifier = Modifier.testTag("settings_battery"),
            onClick = onBatteryOptimisation,
        )

        PiDivider(Modifier.padding(vertical = 4.dp))

        PiSettingRow(
            title = stringResource(R.string.settings_forget),
            supporting = stringResource(R.string.settings_forget_body),
            modifier = Modifier.testTag("settings_forget"),
            onClick = { confirmForget = true },
        )

        PiDivider(Modifier.padding(vertical = 4.dp))

        PiSectionLabel(stringResource(R.string.settings_about))
        Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
            Text(stringResource(R.string.settings_about_version), style = MaterialTheme.typography.bodyMedium)
            Text(
                text = stringResource(R.string.settings_about_license),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.settings_about_private),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.chat_forget_title)) },
            text = { Text(stringResource(R.string.settings_forget_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmForget = false
                        onForget()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.chat_forget)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.chat_cancel)) }
            },
        )
    }
}
