package com.tak.lite.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.repository.MessageRepository
import com.tak.lite.repository.ChannelRepository
import com.tak.lite.network.MeshProtocolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChannelInfo(
    val name: String,
    val isPkiEncrypted: Boolean = false
)

@HiltViewModel
class MessageViewModel @Inject constructor(
    private val channelRepository: ChannelRepository,
    private val messageRepository: MessageRepository
) : ViewModel() {
    private val TAG = "MessageViewModel"

    fun getMessages(channelId: String): Flow<List<ChannelMessage>> {
        return messageRepository.messages.map { it[channelId] ?: emptyList() }
    }

    fun getChannelInfo(channelId: String): Flow<ChannelInfo> {
        return if (channelId.startsWith("dm_")) {
            // For direct messages, get info from MessageRepository
            messageRepository.directMessageChannels.map { channels ->
                val channel = channels[channelId]
                ChannelInfo(
                    name = channel?.name ?: channelId,
                    isPkiEncrypted = channel?.isPkiEncrypted ?: false
                )
            }
        } else {
            // For regular channels, get info from ChannelRepository
            channelRepository.channels.map { channels ->
                val channel = channels.find { it.id == channelId }
                ChannelInfo(
                    name = channel?.name ?: channelId,
                    isPkiEncrypted = false
                )
            }
        }
    }

    fun getCurrentUserShortName(): String? {
        return messageRepository.getCurrentUserShortName()
    }

    fun sendMessage(channelId: String, content: String) {
        viewModelScope.launch {
            messageRepository.sendMessage(channelId, content)
        }
    }

    fun getOrCreateDirectMessageChannel(peerId: String, peerLongName: String? = null): DirectMessageChannel {
        return messageRepository.getOrCreateDirectMessageChannel(peerId, peerLongName)
    }
} 