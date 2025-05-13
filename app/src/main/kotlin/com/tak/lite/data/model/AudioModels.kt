package com.tak.lite.data.model

import kotlinx.serialization.Serializable

@Serializable
data class AudioChannel(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = false,
    val members: List<String> = emptyList() // List of user IDs in this channel
)

@Serializable
data class AudioSettings(
    val isMuted: Boolean = false,
    val volume: Int = 50, // 0-100
    val selectedChannelId: String? = null,
    val isPTTHeld: Boolean = false
)