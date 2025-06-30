package com.tak.lite.data.model

import kotlinx.serialization.Serializable

/**
 * Represents the status of a message in the mesh network
 */
enum class MessageStatus {
    SENDING,    // Message is being sent
    SENT,       // Message is sent
    DELIVERED,  // Message was delivered to the mesh
    RECEIVED,   // Message was received by the intended recipient
    FAILED,     // Message failed to be acknowledged by another node
    ERROR       // Message failed to send or was rejected
}

/**
 * Represents a message in a channel
 */
@Serializable
data class ChannelMessage(
    val senderId: String,
    val senderShortName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val requestId: Int,  // ID used to track message delivery status
    var status: MessageStatus = MessageStatus.SENDING,
    val channelId: String  // Make channelId non-null
)

/**
 * Base interface for all channel implementations
 */
interface IChannel {
    val id: String
    val name: String
    val displayName: String?
    val isDefault: Boolean
    val members: List<String>
    val precision: Int?
    val lastMessage: ChannelMessage?
    val index: Int
    val isPkiEncrypted: Boolean
    val isSelectableForPrimaryTraffic: Boolean
    val allowDelete: Boolean
    val readyToSend: Boolean
    
    fun copy(): IChannel
}

/**
 * Layer 2 mesh networking channel implementation
 */
@Serializable
data class Layer2Channel(
    override val id: String,
    override val name: String,
    override val displayName: String?,
    override val isDefault: Boolean = false,
    override val members: List<String> = emptyList(),
    override val precision: Int? = null,
    override val lastMessage: ChannelMessage? = null,
    override val index: Int = 0,
    override val isPkiEncrypted: Boolean = false,
    override val isSelectableForPrimaryTraffic: Boolean = true,
    override val allowDelete: Boolean = true,
    override val readyToSend: Boolean = true
) : IChannel {
    override fun copy(): IChannel = Layer2Channel(
        id, name, displayName, isDefault, members, precision, lastMessage, index, isPkiEncrypted, isSelectableForPrimaryTraffic, allowDelete
    )
}

/**
 * Meshtastic channel implementation
 */
@Serializable
data class MeshtasticChannel(
    override val id: String,
    override val name: String,
    override val displayName: String,
    override val isDefault: Boolean = false,
    override val members: List<String> = emptyList(),
    val role: ChannelRole = ChannelRole.DISABLED,
    val psk: ByteArray? = null,
    val uplinkEnabled: Boolean = false,
    val downlinkEnabled: Boolean = false,
    val positionPrecision: Int = 0,
    val isClientMuted: Boolean = false,
    override val lastMessage: ChannelMessage? = null,
    override val index: Int = 0,
    override val isPkiEncrypted: Boolean = false,
    override val isSelectableForPrimaryTraffic: Boolean = true,
    override val allowDelete: Boolean = false,
    override val readyToSend: Boolean = true
) : IChannel {
    override val precision: Int?
        get() = positionPrecision

    enum class ChannelRole {
        DISABLED,
        PRIMARY,
        SECONDARY
    }

    override fun copy(): IChannel = MeshtasticChannel(
        id, name, displayName, isDefault, members, role, psk, uplinkEnabled, downlinkEnabled, positionPrecision, isClientMuted, lastMessage, index, isPkiEncrypted, isSelectableForPrimaryTraffic, allowDelete
    )
}

/**
 * Represents a direct message channel between two peers
 */
@Serializable
data class DirectMessageChannel(
    override val id: String,  // Format: "dm_${peerId}"
    override val name: String,  // Peer's longname
    override val displayName: String?,
    override val isDefault: Boolean = false,
    override val members: List<String> = emptyList(),
    override val precision: Int? = null,
    override val lastMessage: ChannelMessage? = null,
    override val index: Int = -1,  // Direct messages don't use channel index
    override val isPkiEncrypted: Boolean = false,
    override val isSelectableForPrimaryTraffic: Boolean = false,
    override val allowDelete: Boolean = true,
    override val readyToSend: Boolean = true,
    val peerId: String
) : IChannel {
    companion object {
        fun createId(peerId: String): String = "dm_$peerId"
    }

    override fun copy(): IChannel = DirectMessageChannel(
        id, name, displayName, isDefault, members, precision, lastMessage, index, isPkiEncrypted, isSelectableForPrimaryTraffic, allowDelete, readyToSend, peerId
    )
}