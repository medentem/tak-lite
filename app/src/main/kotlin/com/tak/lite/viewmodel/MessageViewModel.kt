package com.tak.lite.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.repository.MessageRepository
import com.tak.lite.repository.ChannelRepository
import com.tak.lite.network.MeshProtocolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val messageRepository: MessageRepository,
    private val meshProtocolProvider: MeshProtocolProvider
) : ViewModel() {
    private val TAG = "MessageViewModel"

    fun getMessages(channelId: String): Flow<List<ChannelMessage>> {
        return messageRepository.messages.map { it[channelId] ?: emptyList() }
    }

    fun getChannelName(channelId: String): Flow<String> {
        return channelRepository.channels.map { channels ->
            channels.find { it.id == channelId }?.name ?: channelId
        }
    }

    fun getCurrentUserShortName(): String? {
        val protocol = meshProtocolProvider.protocol.value
        val nodeId = protocol.localNodeIdOrNickname
        if (protocol is com.tak.lite.network.MeshtasticBluetoothProtocol) {
            return protocol.getNodeInfoForPeer(nodeId ?: "")?.user?.shortName
        }
        return nodeId
    }

    fun sendMessage(channelId: String, content: String) {
        viewModelScope.launch {
            try {
                // Just send the message through the protocol
                val protocol = meshProtocolProvider.protocol.value
                protocol.sendTextMessage(channelId, content)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending message: ${e.message}")
            }
        }
    }
} 