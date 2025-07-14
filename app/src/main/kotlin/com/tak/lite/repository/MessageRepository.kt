package com.tak.lite.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.notification.MessageNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider,
    private val messageNotificationManager: MessageNotificationManager,
    private val context: Context
) {
    private val _messages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChannelMessage>>> = _messages.asStateFlow()

    private val TAG = "MessageRepository"

    // Create a coroutine scope for the repository
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Track the current channel messages observation job
    private var channelMessagesJob: Job? = null

    init {
        Log.d(TAG, "=== MessageRepository Constructor ===")
        Log.d(TAG, "MessageRepository is being created")
        Log.d(TAG, "Protocol provider: ${meshProtocolProvider.javaClass.simpleName}")
        
        // Observe messages from the protocol
        repositoryScope.launch {
            Log.d(TAG, "Starting to observe protocol changes")
            meshProtocolProvider.protocol.collect { protocol ->
                Log.d(TAG, "=== MessageRepository Protocol Change ===")
                Log.d(TAG, "Protocol changed to: ${protocol.javaClass.simpleName}")
                
                // Cancel any existing channel messages observation
                channelMessagesJob?.cancel()
                
                // Start observing channel messages for this protocol
                Log.d(TAG, "Starting to observe channel messages for new protocol")
                channelMessagesJob = launch {
                    protocol.channelMessages.collect { channelMessages ->
                        Log.d(TAG, "=== MessageRepository Channel Messages Update ===")
                        Log.d(TAG, "Received channel messages update")
                        Log.d(TAG, "Number of channels with messages: ${channelMessages.size}")
                        channelMessages.forEach { (channelId, messages) ->
                            Log.d(TAG, "Channel $channelId has ${messages.size} messages")
                            if (messages.isNotEmpty()) {
                                Log.d(TAG, "Last message in $channelId: ${messages.last()}")
                            }
                        }
                        
                        // Check for new messages in each channel
                        val previousMessages = _messages.value
                        channelMessages.forEach { (channelId, messages) ->
                            val previousChannelMessages = previousMessages[channelId] ?: emptyList()
                            val newMessages = messages.filter { newMessage ->
                                !previousChannelMessages.any { it.timestamp == newMessage.timestamp &&
                                                             it.senderShortName == newMessage.senderShortName &&
                                                             it.content == newMessage.content }
                            }

                            // Show notifications for new messages
                            newMessages.forEach { message ->
                                if (protocol.localNodeIdOrNickname.value == message.senderId) {
                                    Log.d(TAG, "Message sent from our node, skipping notification")
                                } else {
                                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
                                        Log.d(TAG, "Showing notification for new message in channel $channelId")
                                        val channelName = protocol.getChannelName(channelId) ?: channelId
                                        messageNotificationManager.showMessageNotification(
                                            channelId = channelId,
                                            channelName = channelName,
                                            message = message.content,
                                            message.senderShortName
                                        )
                                    } else {
                                        Log.d(TAG, "No notification permission, skipping notification")
                                    }
                                }
                            }
                        }

                        // Update our messages state
                        _messages.value = channelMessages
                        Log.d(TAG, "Updated _messages.value, total channels: ${_messages.value.size}")
                        Log.d(TAG, "=== MessageRepository Update Complete ===")
                    }
                }
            }
        }
        Log.d(TAG, "=== MessageRepository Constructor Complete ===")
    }

    fun getCurrentUserShortName(): String {
        val protocol = meshProtocolProvider.protocol.value
        val nodeId = protocol.localNodeIdOrNickname.value
        return protocol.getPeerName(nodeId ?: "") ?: "Unknown Peer"
    }

    fun getCurrentUserId(): String? {
        val protocol = meshProtocolProvider.protocol.value
        return protocol.localNodeIdOrNickname.value
    }

    fun sendMessage(channelId: String, content: String) {
        try {
            // Check if this is a direct message channel
            if (channelId.startsWith("dm_")) {
                val peerId = channelId.substring(3) // Remove "dm_" prefix
                sendDirectMessage(peerId, content)
                return
            }
            
            // Regular channel message
            val protocol = meshProtocolProvider.protocol.value
            protocol.sendTextMessage(channelId, content)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending message: ${e.message}")
        }
    }

    fun getOrCreateDirectMessageChannel(peerId: String, peerLongName: String? = null): DirectMessageChannel? {
        // Regular channel message
        val protocol = meshProtocolProvider.protocol.value
        val newChannel = protocol.getOrCreateDirectMessageChannel(peerId)

        return newChannel
    }

    private fun sendDirectMessage(peerId: String, content: String) {
        try {
            val protocol = meshProtocolProvider.protocol.value
            // Send the message through the protocol
            protocol.sendDirectMessage(peerId, content)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending direct message: ${e.message}")
        }
    }
} 