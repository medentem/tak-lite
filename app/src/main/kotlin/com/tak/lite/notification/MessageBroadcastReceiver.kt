package com.tak.lite.notification

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import com.tak.lite.repository.MessageRepository
import com.tak.lite.network.MeshProtocolProvider
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import android.app.NotificationManager

@AndroidEntryPoint
class MessageBroadcastReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "MessageBroadcastReceiver"
    }

    @Inject
    lateinit var messageRepository: MessageRepository
    
    @Inject
    lateinit var meshProtocolProvider: MeshProtocolProvider

    private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        if (intent.action == "com.tak.lite.ACTION_REPLY") {
            val channelId = intent.getStringExtra("channel_id") ?: return
            val smartReply = intent.getStringExtra("smart_reply")
            
            receiverScope.launch {
                try {
                    if (smartReply != null) {
                        // Handle smart reply action
                        Log.d(TAG, "Sending smart reply: $smartReply to channel: $channelId")
                        messageRepository.sendMessage(channelId, smartReply)
                    } else {
                        // Handle manual reply
                        val remoteInput = androidx.core.app.RemoteInput.getResultsFromIntent(intent)
                        val replyText = remoteInput?.getCharSequence("key_text_reply")?.toString()
                        
                        if (!replyText.isNullOrEmpty()) {
                            Log.d(TAG, "Sending manual reply: $replyText to channel: $channelId")
                            messageRepository.sendMessage(channelId, replyText)
                        }
                    }
                    
                    // Wait for message status to update
                    val protocol = meshProtocolProvider.protocol.value
                    protocol.channelMessages.collect { channelMessages ->
                        val messages = channelMessages[channelId] ?: emptyList()
                        val lastMessage = messages.lastOrNull()
                        if (lastMessage?.status != com.tak.lite.data.model.MessageStatus.SENDING) {
                            // Message status has been updated, we can stop collecting
                            return@collect
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling reply: ${e.message}", e)
                }
            }
            
            // Dismiss the notification after sending the reply
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.cancel(channelId.hashCode())
        }
    }
} 