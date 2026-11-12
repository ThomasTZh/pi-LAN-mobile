package dev.pi.mobile.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.pi.mobile.PiMobileApp
import dev.pi.mobile.R
import dev.pi.mobile.proto.PiRecord
import dev.pi.mobile.proto.Records
import dev.pi.mobile.session.PiConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/**
 * Keeps the single [PiConnection] alive while the app is in the background, so
 * an in-flight run and its approvals still reach the phone. It never opens a
 * socket of its own: the connection is the process-wide singleton.
 *
 * Skeleton for T2: channels, the ongoing notification, approval/question alerts
 * and finished/error alerts. Android may still kill the process; that closes the
 * socket and the child, which the chat screen reports as "Run interrupted".
 */
class PiAlertsService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var graph: dev.pi.mobile.AppGraph

    @Volatile
    private var alertedForRun = false

    override fun onCreate() {
        super.onCreate()
        graph = (application as PiMobileApp).graph
        PiNotifications.ensureChannels(this)
        startForegroundCompat(getString(R.string.notif_ongoing_connecting))
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PiNotifications.cancelAll(this)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(ongoingText())
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            graph.connection.state.collect { updateOngoing(it) }
        }
        scope.launch {
            graph.connection.records.collect { record -> handleRecord(record) }
        }
    }

    private fun handleRecord(json: JsonObject) {
        when (val record = Records.parse(json)) {
            is PiRecord.AgentStart -> alertedForRun = false

            is PiRecord.ExtensionUiRequest -> {
                val method = record.method.lowercase()
                if (method !in ATTENTION_METHODS) return
                val settings = graph.settingsStore.state.value
                if (!settings.alertApprovals) return
                val title = if (method == "confirm") {
                    getString(R.string.notif_approval_title)
                } else {
                    getString(R.string.notif_question_title)
                }
                val body = record.message ?: record.title ?: getString(R.string.notif_open)
                PiNotifications.post(this, title, body, attention = true, hideDetails = settings.hideDetails)
                alertedForRun = true
            }

            is PiRecord.AgentSettled -> {
                val settings = graph.settingsStore.state.value
                if (alertedForRun) {
                    // A finished alert right after an approval alert is noise.
                    alertedForRun = false
                    return
                }
                if (!settings.alertFinished) return
                PiNotifications.post(
                    this,
                    getString(R.string.notif_finished_title),
                    getString(R.string.notif_open),
                    attention = false,
                    hideDetails = settings.hideDetails,
                )
            }

            is PiRecord.Child -> {
                val fatal = record.kind.lowercase().let { it.contains("exit") || it.contains("error") || it.contains("crash") }
                if (!fatal) return
                val settings = graph.settingsStore.state.value
                if (!settings.alertErrors) return
                PiNotifications.post(
                    this,
                    getString(R.string.notif_error_title),
                    record.detail ?: getString(R.string.notif_open),
                    attention = true,
                    hideDetails = settings.hideDetails,
                )
            }

            else -> Unit
        }
    }

    private fun startForegroundCompat(text: String) {
        ServiceCompat.startForeground(
            this,
            PiNotifications.ONGOING_ID,
            PiNotifications.ongoing(this, text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )
    }

    private fun updateOngoing(state: PiConnection.State) {
        val manager = getSystemService(android.app.NotificationManager::class.java) ?: return
        manager.notify(PiNotifications.ONGOING_ID, PiNotifications.ongoing(this, textFor(state)))
    }

    private fun ongoingText(): String = textFor(graph.connection.state.value)

    private fun textFor(state: PiConnection.State): String = when (state) {
        is PiConnection.State.Connected -> getString(R.string.notif_ongoing_title)
        is PiConnection.State.Connecting -> getString(R.string.notif_ongoing_connecting)
        is PiConnection.State.Reconnecting -> "Reconnecting…"
        is PiConnection.State.Failed -> "Pi disconnected"
        else -> getString(R.string.notif_ongoing_title)
    }

    companion object {
        const val ACTION_START = "dev.pi.mobile.action.START_ALERTS"
        const val ACTION_STOP = "dev.pi.mobile.action.STOP_ALERTS"

        private val ATTENTION_METHODS = setOf("confirm", "select", "input", "editor")

        fun start(context: Context): Boolean = try {
            val intent = Intent(context, PiAlertsService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (_: Exception) {
            false
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PiAlertsService::class.java))
            PiNotifications.cancelAll(context)
        }
    }
}
