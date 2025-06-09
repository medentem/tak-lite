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
    private val messageRepository: MessageRepository
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
        return messageRepository.getCurrentUserShortName()
    }

    fun sendMessage(channelId: String, content: String) {
        viewModelScope.launch {
            messageRepository.sendMessage(channelId, content)
        }
    }
} 