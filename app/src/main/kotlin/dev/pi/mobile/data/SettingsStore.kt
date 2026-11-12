package dev.pi.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User preferences; the connect flow and the alert service both read them. */
data class AppSettings(
    val remember: Boolean = true,
    val alertsEnabled: Boolean = false,
    val alertApprovals: Boolean = true,
    val alertFinished: Boolean = true,
    val alertErrors: Boolean = true,
    val hideDetails: Boolean = false,
    val theme: String = "dark",
    val textScale: Int = 100,
    val keepAwake: Boolean = false,
    val haptics: Boolean = true,
) {
    /** Notification privacy toggle transform, used by ChatController (F1/F17). */
    fun withHideDetails(enabled: Boolean): AppSettings = copy(hideDetails = enabled)
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("pi_settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun read() = AppSettings(
        remember = prefs.getBoolean("remember", true),
        alertsEnabled = prefs.getBoolean("alertsEnabled", false),
        alertApprovals = prefs.getBoolean("alertApprovals", true),
        alertFinished = prefs.getBoolean("alertFinished", true),
        alertErrors = prefs.getBoolean("alertErrors", true),
        hideDetails = prefs.getBoolean("hideDetails", false),
        theme = prefs.getString("theme", "dark") ?: "dark",
        textScale = prefs.getInt("textScale", 100).coerceIn(85, 160),
        keepAwake = prefs.getBoolean("keepAwake", false),
        haptics = prefs.getBoolean("haptics", true),
    )

    fun update(transform: (AppSettings) -> AppSettings): AppSettings {
        val next = transform(_state.value)
        prefs.edit()
            .putBoolean("remember", next.remember)
            .putBoolean("alertsEnabled", next.alertsEnabled)
            .putBoolean("alertApprovals", next.alertApprovals)
            .putBoolean("alertFinished", next.alertFinished)
            .putBoolean("alertErrors", next.alertErrors)
            .putBoolean("hideDetails", next.hideDetails)
            .putString("theme", next.theme)
            .putInt("textScale", next.textScale.coerceIn(85, 160))
            .putBoolean("keepAwake", next.keepAwake)
            .putBoolean("haptics", next.haptics)
            .apply()
        _state.value = next
        return next
    }

    fun reload() {
        _state.value = read()
    }
}
