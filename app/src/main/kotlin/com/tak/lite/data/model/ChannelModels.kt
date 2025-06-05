package com.tak.lite.data.model

import kotlinx.serialization.Serializable

@Serializable
data class Channel(
    val id: String,
    val name: String,
    val isDefault: Boolean = false,
    val isActive: Boolean = false,
    val members: List<String> = emptyList() // List of user IDs in this channel
)