package com.tak.lite.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.ChannelSettings
import com.tak.lite.data.model.IChannel
import com.tak.lite.repository.ChannelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    private val channelRepository: ChannelRepository
) : ViewModel() {
    private val TAG = "ChannelViewModel"

    val settings: StateFlow<ChannelSettings> = channelRepository.settings

    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()

    init {
        Log.d(TAG, "Initializing ChannelViewModel")
        viewModelScope.launch {
            Log.d(TAG, "Starting to collect channels from repository")
            channelRepository.channels.collect { newChannels ->
                Log.d(TAG, "Received new channels from repository. Count: ${newChannels.size}")
                newChannels.forEach { channel ->
                    Log.d(TAG, "Channel: id=${channel.id}, name=${channel.name}")
                }
                _channels.value = newChannels
            }
        }
    }

    fun createChannel(name: String) {
        Log.d(TAG, "Creating new channel: $name")
        viewModelScope.launch {
            channelRepository.createChannel(name)
        }
    }

    fun deleteChannel(channelId: String) {
        Log.d(TAG, "Deleting channel: $channelId")
        viewModelScope.launch {
            channelRepository.deleteChannel(channelId)
        }
    }

    fun selectChannel(channelId: String) {
        Log.d(TAG, "Selecting channel: $channelId")
        viewModelScope.launch {
            channelRepository.selectChannel(channelId)
        }
    }
} 