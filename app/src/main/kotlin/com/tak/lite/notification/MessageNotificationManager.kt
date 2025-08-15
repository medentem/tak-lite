package com.tak.lite.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestion
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import com.tak.lite.MessageActivity
import com.tak.lite.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageNotificationManager @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val CHANNEL_ID = "taklite_messages"
        private const val KEY_TEXT_REPLY = "key_text_reply"
        private const val ACTION_REPLY = "com.tak.lite.ACTION_REPLY"
    }

    private val smartReply = SmartReply.getClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            enableVibration(true)
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }

    fun showMessageNotification(channelId: String, channelName: String, message: String, fromShortName: String) {
        Log.d("MessageNotificationManager", "Creating notification for channel: $channelName, message: $message")

        val fromShortNameDefaulted = fromShortName.ifEmpty {
            context.getString(R.string.notification_unknown_sender)
        }

        // Create conversation history for smart reply
        val conversationHistory = listOf(
            TextMessage.createForRemoteUser(message, System.currentTimeMillis(), fromShortNameDefaulted)
        )

        // Generate smart reply suggestions
        coroutineScope.launch {
            try {
                val result = smartReply.suggestReplies(conversationHistory).await()
                if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                    val suggestions = result.suggestions
                    showNotificationWithSmartReplies(channelId, channelName, message, fromShortNameDefaulted, suggestions)
                } else {
                    showBasicNotification(channelId, channelName, message, fromShortNameDefaulted)
                }
            } catch (e: Exception) {
                Log.e("MessageNotificationManager", "Error getting smart reply suggestions", e)
                showBasicNotification(channelId, channelName, message, fromShortNameDefaulted)
            }
        }
    }

    private fun showNotificationWithSmartReplies(
        channelId: String,
        channelName: String,
        message: String,
        fromShortName: String,
        suggestions: List<SmartReplySuggestion>
    ) {
        val intent = MessageActivity.createIntent(context, channelId).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (PendingIntent.FLAG_IMMUTABLE)
        )

        // Create reply action
        val remoteInput = RemoteInput.Builder(KEY_TEXT_REPLY)
            .setLabel(context.getString(R.string.notification_reply))
            .build()

        val replyIntent = Intent(context, MessageBroadcastReceiver::class.java).apply {
            action = ACTION_REPLY
            putExtra("channel_id", channelId)
        }

        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            channelId.hashCode(),
            replyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        val replyAction = NotificationCompat.Action.Builder(
            R.drawable.ic_reply,
            context.getString(R.string.notification_reply),
            replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        // Create smart reply actions
        val smartReplyActions = suggestions.take(3).map { suggestion ->
            val smartReplyIntent = Intent(context, MessageBroadcastReceiver::class.java).apply {
                action = ACTION_REPLY
                putExtra("channel_id", channelId)
                putExtra("smart_reply", suggestion.text)
            }

            val smartReplyPendingIntent = PendingIntent.getBroadcast(
                context,
                "${channelId}_${suggestion.text}".hashCode(),
                smartReplyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )

            NotificationCompat.Action.Builder(
                R.drawable.ic_reply,
                suggestion.text,
                smartReplyPendingIntent
            ).build()
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New message from $fromShortName in $channelName")
            .setContentText(message)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(replyAction)
            .apply {
                smartReplyActions.forEach { action ->
                    addAction(action)
                }
            }
            .build()

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        Log.d("MessageNotificationManager", "Posting notification with ID: ${channelId.hashCode()}")
        notificationManager.notify(channelId.hashCode(), notification)
    }

    private fun showBasicNotification(channelId: String, channelName: String, message: String, fromShortName: String) {
        val intent = MessageActivity.createIntent(context, channelId).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or (PendingIntent.FLAG_IMMUTABLE)
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New message from $fromShortName in $channelName")
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