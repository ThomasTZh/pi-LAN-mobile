package dev.pi.mobile

import android.content.Context
import dev.pi.mobile.data.ProfileStore
import dev.pi.mobile.data.SecretStore
import dev.pi.mobile.data.SettingsStore
import dev.pi.mobile.session.PiConnection

/** Process-wide dependencies shared by the activity and the alert service. */
class AppGraph(context: Context) {
    val appContext: Context = context.applicationContext
    val profileStore = ProfileStore(appContext)
    val secretStore = SecretStore(appContext)
    val settingsStore = SettingsStore(appContext)
    val connection: PiConnection = PiConnection.shared()
}
