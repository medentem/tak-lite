package com.tak.lite.data.model

import kotlinx.serialization.Serializable

/**
 * Represents a quick message that can be sent to the selected channel
 */
@Serializable
data class QuickMessage(
    val id: Int, // 0-5 for 6 messages
    val text: String,
    val isDefault: Boolean = false
) {
    companion object {
        /**
         * Default quick messages that will be used on first app launch
         */
        val DEFAULT_MESSAGES = listOf(
            QuickMessage(0, "All clear", true),
            QuickMessage(1, "Hold Position", true),
            QuickMessage(2, "Rally to me", true),
            QuickMessage(3, "Need help", true),
            QuickMessage(4, "I'm good", true),
            QuickMessage(5, "Attention to my position", true)
        )
    }
}
