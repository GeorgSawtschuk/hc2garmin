package com.example.hc2garmin.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.hc2garmin.R
import com.example.hc2garmin.domain.model.WeightMeasurement
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.*

object NotificationHelper {
    private const val CHANNEL_ID = "sync_notifications"
    private const val NOTIFICATION_ID = 1001

    fun showSyncNotification(context: Context, measurement: WeightMeasurement) {
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

        val formatter = DateTimeFormatter.ofPattern("dd.MM.yyyy, HH:mm")
            .withZone(ZoneId.systemDefault())
        val dateStr = formatter.format(Instant.ofEpochSecond(measurement.epochSeconds))

        val title = context.getString(R.string.notification_title)
        val text = context.getString(R.string.notification_text_format, measurement.weightKg, dateStr)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
