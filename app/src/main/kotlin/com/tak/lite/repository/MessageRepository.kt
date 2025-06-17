package com.tak.lite.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.notification.MessageNotificationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.MessageStatus

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

    init {
        // Observe messages from the protocol
        repositoryScope.launch {
            meshProtocolProvider.protocol.collect { protocol ->
                // Observe channel messages
                var previousMessages = _messages.value
                protocol.channelMessages.collect { channelMessages ->
                    // Check for new messages in each channel
                    channelMessages.forEach { (channelId, messages) ->
                        val previousChannelMessages = previousMessages[channelId] ?: emptyList()
                        val newMessages = messages.filter { newMessage ->
                            !previousChannelMessages.any { it.timestamp == newMessage.timestamp &&
                                                         it.senderShortName == newMessage.senderShortName &&
                                                         it.content == newMessage.content }
                        }

                        // Show notifications for new messages
                        newMessages.forEach { message ->
                            if (meshProtocolProvider.protocol.value.localNodeIdOrNickname.value == message.senderId) {
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
                    previousMessages = channelMessages
                }
            }
        }
    }

    fun getCurrentUserShortName(): String? {
        val protocol = meshProtocolProvider.protocol.value
        val nodeId = protocol.localNodeIdOrNickname.value
        if (protocol is com.tak.lite.network.MeshtasticBluetoothProtocol) {
            return protocol.getNodeInfoForPeer(nodeId ?: "")?.user?.shortName
        }
        return nodeId
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