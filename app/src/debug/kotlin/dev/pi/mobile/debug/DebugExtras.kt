package dev.pi.mobile.debug

import android.content.Intent
import dev.pi.mobile.BuildConfig

/**
 * Debug-only intent extras used by `pi-app/tools/e2e.sh` (PLAN.md 7):
 * `pi_host`, `pi_pin`, `pi_debug_autoconnect`, `pi_debug_prompt`.
 */
object DebugExtras {
    const val EXTRA_HOST = "pi_host"
    const val EXTRA_PIN = "pi_pin"
    const val EXTRA_AUTOCONNECT = "pi_debug_autoconnect"
    const val EXTRA_PROMPT = "pi_debug_prompt"

    fun from(intent: Intent?): DebugLaunch? {
        if (!BuildConfig.DEBUG || intent == null) return null
        val host = intent.getStringExtra(EXTRA_HOST)
        val pin = intent.getStringExtra(EXTRA_PIN)
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
        if (host.isNullOrBlank() && pin.isNullOrBlank() && prompt.isNullOrBlank()) return null
        return DebugLaunch(
            host = host?.takeIf { it.isNotBlank() },
            pin = pin?.takeIf { it.isNotBlank() },
            autoConnect = intent.getBooleanExtra(EXTRA_AUTOCONNECT, false) ||
                (!host.isNullOrBlank() && !pin.isNullOrBlank()),
            prompt = prompt?.takeIf { it.isNotBlank() },
        )
    }
}
