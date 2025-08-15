package com.tak.lite.repository

import android.content.Context
import android.util.Log
import com.tak.lite.data.model.ChannelSettings
import com.tak.lite.data.model.IChannel
import com.tak.lite.network.MeshNetworkService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChannelRepository @Inject constructor(
    private val meshNetworkService: MeshNetworkService,
    context: Context
) {
    private val TAG = "ChannelRepository"
    private val prefs = context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE)

    val channels: Flow<List<IChannel>>
        get() = meshNetworkService.channels

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<ChannelSettings> = _settings.asStateFlow()

    init {
        Log.d(TAG, "Initializing ChannelRepository")
    }

    private fun loadSettings(): ChannelSettings {
        val selectedChannelId = prefs.getString("selected_channel_id", null)
        return ChannelSettings(selectedChannelId = selectedChannelId)
    }

    suspend fun createChannel(name: String) {
        Log.d(TAG, "Creating new channel: $name")
        meshNetworkService.createChannel(name)
    }

    suspend fun deleteChannel(channelId: String) {
        Log.d(TAG, "Deleting channel: $channelId")
        meshNetworkService.deleteChannel(channelId)
        // If deleted channel was selected, update settings
        if (_settings.value.selectedChannelId == channelId) {
            val currentChannels = channels.first()
            val first = currentChannels.firstOrNull()
            Log.d(TAG, "Updating selected channel after deletion to: ${first?.name}")
            _settings.value = _settings.value.copy(selectedChannelId = first?.id)
            prefs.edit().putString("selected_channel_id", first?.id).apply()
        }
    }

    suspend fun selectChannel(channelId: String) {
        Log.d(TAG, "Selecting channel: $channelId")
        meshNetworkService.selectChannel(channelId)
        _settings.value = _settings.value.copy(selectedChannelId = channelId)
        prefs.edit().putString("selected_channel_id", channelId).apply()
    }

    fun resetChannelSelection() {
        Log.d(TAG, "Resetting channel selection")
        _settings.value = _settings.value.copy(selectedChannelId = null)
        prefs.edit().remove("selected_channel_id").apply()
    }
} 