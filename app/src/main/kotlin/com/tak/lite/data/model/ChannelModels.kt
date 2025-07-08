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
    ERROR;      // Message failed to send or was rejected

    /**
     * Returns the ordinal value representing the progression of this status.
     * Higher values represent more advanced states in the message lifecycle.
     */
    fun getProgressionValue(): Int = when (this) {
        SENDING -> 0
        SENT -> 1
        DELIVERED -> 2
        RECEIVED -> 3
        FAILED -> 4  // Terminal state
        ERROR -> 4   // Terminal state
    }

    /**
     * Checks if this status can be transitioned to from the given current status.
     * Returns true if the new status represents progress or is a valid terminal state.
     */
    fun canTransitionFrom(currentStatus: MessageStatus): Boolean {
        // Terminal states (FAILED, ERROR) can be reset by queueing
        if (currentStatus == FAILED || currentStatus == ERROR) {
            return true
        }
        
        // If the new status is also a terminal state, it can always be set
        if (this == FAILED || this == ERROR) {
            return true
        }
        
        // For non-terminal states, only allow progression forward
        return this.getProgressionValue() >= currentStatus.getProgressionValue()
    }

    /**
     * Checks if this status represents a terminal state that cannot be overridden.
     */
    fun isTerminal(): Boolean = this == FAILED || this == ERROR
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
) {
    /**
     * Safely updates the message status, preventing backward transitions.
     * Returns true if the status was updated, false if the update was rejected.
     */
    fun updateStatus(newStatus: MessageStatus): Boolean {
        return if (newStatus.canTransitionFrom(status)) {
            status = newStatus
            true
        } else {
            false
        }
    }

    /**
     * Creates a copy of this message with the new status, but only if the transition is valid.
     * Returns null if the status transition is not allowed.
     */
    fun copyWithStatus(newStatus: MessageStatus): ChannelMessage? {
        return if (newStatus.canTransitionFrom(status)) {
            copy(status = newStatus)
        } else {
            null
        }
    }
}

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