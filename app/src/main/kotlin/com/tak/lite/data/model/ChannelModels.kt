package com.tak.lite.data.model

import kotlinx.serialization.Serializable

/**
 * Base interface for all channel implementations
 */
interface IChannel {
    val id: String
    val name: String
    val isDefault: Boolean
    val isActive: Boolean
    val members: List<String>
}

/**
 * Layer 2 mesh networking channel implementation
 */
@Serializable
data class Layer2Channel(
    override val id: String,
    override val name: String,
    override val isDefault: Boolean = false,
    override val isActive: Boolean = false,
    override val members: List<String> = emptyList()
) : IChannel

/**
 * Meshtastic channel implementation
 */
@Serializable
data class MeshtasticChannel(
    override val id: String,
    override val name: String,
    override val isDefault: Boolean = false,
    override val isActive: Boolean = false,
    override val members: List<String> = emptyList(),
    val role: ChannelRole = ChannelRole.DISABLED,
    val psk: ByteArray? = null,
    val uplinkEnabled: Boolean = false,
    val downlinkEnabled: Boolean = false,
    val positionPrecision: Int = 0,
    val isClientMuted: Boolean = false
) : IChannel {
    enum class ChannelRole {
        DISABLED,
        PRIMARY,
        SECONDARY
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as MeshtasticChannel

        if (id != other.id) return false
        if (name != other.name) return false
        if (isDefault != other.isDefault) return false
        if (isActive != other.isActive) return false
        if (members != other.members) return false
        if (role != other.role) return false
        if (psk != null) {
            if (other.psk == null) return false
            if (!psk.contentEquals(other.psk)) return false
        } else if (other.psk != null) return false
        if (uplinkEnabled != other.uplinkEnabled) return false
        if (downlinkEnabled != other.downlinkEnabled) return false
        if (positionPrecision != other.positionPrecision) return false
        if (isClientMuted != other.isClientMuted) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isDefault.hashCode()
        result = 31 * result + isActive.hashCode()
        result = 31 * result + members.hashCode()
        result = 31 * result + role.hashCode()
        result = 31 * result + (psk?.contentHashCode() ?: 0)
        result = 31 * result + uplinkEnabled.hashCode()
        result = 31 * result + downlinkEnabled.hashCode()
        result = 31 * result + positionPrecision
        result = 31 * result + isClientMuted.hashCode()
        return result
    }
}

// For backward compatibility
@Deprecated("Use Layer2Channel instead", ReplaceWith("Layer2Channel"))
typealias Channel = Layer2Channel