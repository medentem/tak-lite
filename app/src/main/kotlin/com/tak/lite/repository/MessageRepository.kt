package com.tak.lite.repository

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.tak.lite.data.model.ChannelMessage
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

@Singleton
class MessageRepository @Inject constructor(
    private val meshProtocolProvider: MeshProtocolProvider,
    private val messageNotificationManager: MessageNotificationManager,
    private val context: Context
) {
    private val _messages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    val messages: StateFlow<Map<String, List<ChannelMessage>>> = _messages.asStateFlow()

    // Create a coroutine scope for the repository
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Observe messages from the protocol
        repositoryScope.launch {
            meshProtocolProvider.protocol.collect { protocol ->
                when (protocol) {
                    is com.tak.lite.network.MeshtasticBluetoothProtocol -> {
                        protocol.channelMessages.collect { channelMessages ->
                            _messages.value = channelMessages
                        }
                    }
                    else -> {
                        // Other protocols don't support messages yet
                    }
                }
            }
        }
    }

    fun addMessage(channelId: String, message: ChannelMessage) {
        val currentMessages = _messages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _messages.value = currentMessages

        // Get channel name from protocol
        val channelName = meshProtocolProvider.protocol.value.getChannelName(channelId) ?: channelId
        
        // Show notification if we have permission
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || 
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            messageNotificationManager.showMessageNotification(
                channelId = channelId,
                channelName = channelName,
                message = message.content
            )
        }
    }

    fun getMessages(channelId: String): List<ChannelMessage> {
        return _messages.value[channelId] ?: emptyList()
    }

    fun clearMessages(channelId: String) {
        val currentMessages = _messages.value.toMutableMap()
        currentMessages.remove(channelId)
        _messages.value = currentMessages
    }
} 