package com.tak.lite.data.model

import kotlinx.serialization.Serializable

@Serializable
data class ChannelSettings(
    val isMuted: Boolean = false,
    val volume: Int = 50, // 0-100
    val selectedChannelId: String? = null,
    val isPTTHeld: Boolean = false,
    val endOfTransmissionBeep: Boolean = true // New field for end of transmission beep
)