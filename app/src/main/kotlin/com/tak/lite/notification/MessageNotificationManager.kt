package com.tak.lite.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.tak.lite.R
import com.tak.lite.ui.message.MessageActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotificationManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "taklite_messages"
        private const val NOTIFICATION_ID = 3001
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Messages",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications for new messages"
                enableVibration(true)
            }
            val notificationManager = context.getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun showMessageNotification(channelId: String, channelName: String, message: String) {
        Log.d("MessageNotificationManager", "Creating notification for channel: $channelName, message: $message")
        val intent = Intent(context, MessageActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("channel_id", channelId)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New message in $channelName")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Log.d("MessageNotificationManager", "Posting notification with ID: ${channelId.hashCode()}")
        notificationManager.notify(channelId.hashCode(), notification)
    }
} 