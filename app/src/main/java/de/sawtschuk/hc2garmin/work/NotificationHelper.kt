package de.sawtschuk.hc2garmin.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import de.sawtschuk.hc2garmin.R
import de.sawtschuk.hc2garmin.domain.model.WeightMeasurement

object NotificationHelper {
    private const val CHANNEL_ID = "sync_notifications"
    private const val NOTIFICATION_ID = 1001

    fun showSyncNotification(
        context: Context,
        measurement: WeightMeasurement?,
        bpCount: Int = 0
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notification_channel_description)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val title = context.getString(R.string.notification_title)

        val text = buildNotificationText(context, measurement, bpCount)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotificationText(
        context: Context,
        measurement: WeightMeasurement?,
        bpCount: Int
    ): String {
        val weightPart = if (measurement != null) {
            context.resources.getQuantityString(R.plurals.notification_weight_synced, 1, 1)
        } else null

        val bpPart = if (bpCount > 0) {
            context.resources.getQuantityString(R.plurals.notification_bp_synced, bpCount, bpCount)
        } else null

        return listOfNotNull(weightPart, bpPart).joinToString(" · ")
    }
}
