package dev.pi.mobile.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.core.app.NotificationCompat
import dev.pi.mobile.MainActivity
import dev.pi.mobile.R

/**
 * Notification channels and builders. Approvals use the high-importance
 * channel; progress/finish use the default one; the foreground service uses a
 * low, silent, ongoing notification (PLAN.md 5.6).
 */
object PiNotifications {
    const val CHANNEL_APPROVALS = "pi_approvals"
    const val CHANNEL_UPDATES = "pi_updates"
    const val CHANNEL_ONGOING = "pi_ongoing"
    const val ONGOING_ID = 4101

    private const val ACCENT = "#7C8CFF"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val approvals = NotificationChannel(
            CHANNEL_APPROVALS,
            context.getString(R.string.channel_attention),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_attention_description)
            enableVibration(true)
        }
        val updates = NotificationChannel(
            CHANNEL_UPDATES,
            context.getString(R.string.channel_updates),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.channel_updates_description)
        }
        val ongoing = NotificationChannel(
            CHANNEL_ONGOING,
            context.getString(R.string.channel_service),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_service_description)
            setShowBadge(false)
        }
        manager.createNotificationChannels(listOf(approvals, updates, ongoing))
    }

    /** Ongoing "Pi connected" notification with a Stop action. */
    fun ongoing(context: Context, text: String): Notification {
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, PiAlertsService::class.java).setAction(PiAlertsService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_stat_pi)
            .setColor(Color.parseColor(ACCENT))
            .setContentTitle(context.getString(R.string.notif_ongoing_title))
            .setContentText(text)
            .setContentIntent(openIntent(context))
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.notif_stop), stop)
            .build()
    }

    fun post(
        context: Context,
        title: String,
        body: String,
        attention: Boolean,
        hideDetails: Boolean,
    ) {
        val channel = if (attention) CHANNEL_APPROVALS else CHANNEL_UPDATES
        val content = alertContent(
            title = title,
            body = body,
            hideDetails = hideDetails,
            genericTitle = context.getString(R.string.notif_private_title),
            genericBody = context.getString(R.string.notif_open),
        )
        val builder = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pi)
            .setColor(Color.parseColor(ACCENT))
            .setContentTitle(content.title)
            .setContentText(content.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content.body))
            .setContentIntent(openIntent(context))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setPriority(if (attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setVisibility(content.visibility)

        // The lock screen only ever gets generic text, whatever the alert says.
        val publicContent = publicContent(
            genericTitle = context.getString(R.string.notif_private_title),
            genericBody = context.getString(R.string.notif_private_body),
        )
        val publicVersion = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_pi)
            .setContentTitle(publicContent.title)
            .setContentText(publicContent.body)
            .build()
        builder.setPublicVersion(publicVersion)

        context.getSystemService(NotificationManager::class.java)
            ?.notify(notificationId(title, attention), builder.build())
    }

    /** Title, body and lock-screen visibility for one alert. */
    data class AlertContent(val title: String, val body: String, val visibility: Int)

    /**
     * Pure privacy decision, unit-tested in `PiNotificationsPrivacyTest`:
     * `hideDetails` swaps in the generic strings and asks for
     * [NotificationCompat.VISIBILITY_SECRET]; otherwise the alert text stays and
     * the notification is only [NotificationCompat.VISIBILITY_PRIVATE].
     */
    fun alertContent(
        title: String,
        body: String,
        hideDetails: Boolean,
        genericTitle: String,
        genericBody: String,
    ): AlertContent = if (hideDetails) {
        AlertContent(genericTitle, genericBody, NotificationCompat.VISIBILITY_SECRET)
    } else {
        AlertContent(title, body, NotificationCompat.VISIBILITY_PRIVATE)
    }

    /** The redacted copy Android may show on the lock screen. */
    fun publicContent(genericTitle: String, genericBody: String): AlertContent =
        AlertContent(genericTitle, genericBody, NotificationCompat.VISIBILITY_PRIVATE)

    fun cancelAll(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancelAll()
    }

    private fun notificationId(title: String, attention: Boolean): Int =
        4300 + ((title.hashCode() and 0x7FFFFFFF) + if (attention) 1 else 0) % 1_000_000

    private fun openIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
