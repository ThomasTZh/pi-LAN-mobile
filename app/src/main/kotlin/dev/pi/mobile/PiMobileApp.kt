package dev.pi.mobile

import android.app.Application
import dev.pi.mobile.service.PiNotifications

class PiMobileApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        PiNotifications.ensureChannels(this)
    }
}
