package dev.pi.mobile.debug

import android.content.Intent

/** Release variant: debug launch extras are compiled out. */
object DebugExtras {
    fun from(intent: Intent?): DebugLaunch? = null
}
