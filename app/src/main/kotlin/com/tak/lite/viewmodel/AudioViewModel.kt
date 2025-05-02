package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.AudioChannel
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.network.MeshNetworkManagerImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val meshNetworkManager: MeshNetworkManagerImpl
) : ViewModel() {

    private val _channels = MutableStateFlow<List<AudioChannel>>(emptyList())
    val channels: StateFlow<List<AudioChannel>> = _channels.asStateFlow()

    private val _settings = MutableStateFlow(AudioSettings())
    val settings: StateFlow<AudioSettings> = _settings.asStateFlow()

    private val _connectionState = MutableStateFlow(MeshNetworkManagerImpl.ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<MeshNetworkManagerImpl.ConnectionState> = _connectionState.asStateFlow()

    fun connect() {
        viewModelScope.launch {
            meshNetworkManager.connect()
        }
    }

    fun selectChannel(channelId: String) {
        viewModelScope.launch {
            meshNetworkManager.selectChannel(channelId)
        }
    }

    fun setPTTState(isPressed: Boolean) {
        viewModelScope.launch {
            meshNetworkManager.setPTTState(isPressed)
        }
    }

    fun setVolume(volume: Int) {
        viewModelScope.launch {
            meshNetworkManager.setVolume(volume)
            _settings.value = _settings.value.copy(volume = volume)
        }
    }

    fun toggleMute() {
        viewModelScope.launch {
            val currentMuteState = _settings.value.isMuted
            meshNetworkManager.setMute(!currentMuteState)
            _settings.value = _settings.value.copy(isMuted = !currentMuteState)
        }
    }

    fun createChannel(name: String) {
        viewModelScope.launch {
            meshNetworkManager.createChannel(name)
        }
    }

    fun deleteChannel(channelId: String) {
        viewModelScope.launch {
            meshNetworkManager.deleteChannel(channelId)
        }
    }
} 