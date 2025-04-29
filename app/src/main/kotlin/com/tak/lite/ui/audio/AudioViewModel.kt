package com.tak.lite.ui.audio

import android.content.Intent
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.AudioChannel
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.service.AudioStreamingService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val audioService: AudioStreamingService
) : ViewModel() {
    private val _channels = MutableLiveData<List<AudioChannel>>(emptyList())
    val channels: LiveData<List<AudioChannel>> = _channels

    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings

    init {
        // Create default channel
        val defaultChannel = AudioChannel(
            id = UUID.randomUUID().toString(),
            name = "Default Channel",
            isDefault = true,
            isActive = true
        )
        _channels.value = listOf(defaultChannel)
        _settings.value = _settings.value.copy(selectedChannelId = defaultChannel.id)
        
        // Start audio service
        viewModelScope.launch {
            audioService.startStreaming(_settings.value)
        }
    }

    fun createChannel(name: String) {
        val currentChannels = _channels.value ?: emptyList()
        val newChannel = AudioChannel(
            id = UUID.randomUUID().toString(),
            name = name
        )
        _channels.value = currentChannels + newChannel
    }

    fun deleteChannel(channelId: String) {
        val currentChannels = _channels.value ?: emptyList()
        _channels.value = currentChannels.filter { it.id != channelId }
        
        // If deleting selected channel, switch to default
        if (_settings.value.selectedChannelId == channelId) {
            val defaultChannel = currentChannels.find { it.isDefault }
            _settings.value = _settings.value.copy(selectedChannelId = defaultChannel?.id)
        }
    }

    fun selectChannel(channelId: String) {
        _settings.value = _settings.value.copy(selectedChannelId = channelId)
    }

    fun setVolume(volume: Int) {
        _settings.value = _settings.value.copy(volume = volume.coerceIn(0, 100))
    }

    fun toggleMute() {
        _settings.value = _settings.value.copy(isMuted = !_settings.value.isMuted)
    }

    fun setPTTState(isHeld: Boolean) {
        _settings.value = _settings.value.copy(isPTTHeld = isHeld)
    }

    fun addUserToChannel(channelId: String, userId: String) {
        val currentChannels = _channels.value ?: emptyList()
        _channels.value = currentChannels.map { channel ->
            if (channel.id == channelId && !channel.members.contains(userId)) {
                channel.copy(members = channel.members + userId)
            } else {
                channel
            }
        }
    }

    fun removeUserFromChannel(channelId: String, userId: String) {
        val currentChannels = _channels.value ?: emptyList()
        _channels.value = currentChannels.map { channel ->
            if (channel.id == channelId) {
                channel.copy(members = channel.members.filter { it != userId })
            } else {
                channel
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioService.stopStreaming()
    }
} 