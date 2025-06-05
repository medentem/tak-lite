package com.tak.lite.model

import kotlinx.serialization.Serializable

@Serializable
data class PacketHeader(
    val sequenceNumber: Long,
    val packetType: PacketType,
    val timestamp: Long = System.currentTimeMillis(),
    val channelId: String? = null
)

enum class PacketType {
    DISCOVERY,
    LOCATION,
    ANNOTATION,
    AUDIO,
    STATE_SYNC,
    ACK
}

data class ConnectionMetrics(
    val packetLoss: Float = 0f,
    val latency: Long = 0L,
    val jitter: Long = 0L,
    val lastUpdate: Long = System.currentTimeMillis(),
    val networkQuality: Float = 1.0f
)

@Serializable
data class StateVersion(
    val version: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val peerId: String
)

@Serializable
data class StateSyncMessage(
    val type: String = "STATE_SYNC",
    val version: StateVersion,
    val channels: List<com.tak.lite.data.model.AudioChannel>,
    val peerLocations: Map<String, LatLngSerializable>,
    val annotations: List<MapAnnotation>,
    val partialUpdate: Boolean = false,
    val updateFields: Set<String> = emptySet()
)

@Serializable
data class DiscoveryPacket(
    val type: String = "TAK_LITE_DISCOVERY",
    val nickname: String? = null,
    val capabilities: Set<String> = emptySet(),
    val knownPeers: Set<String> = emptySet(),
    val networkQuality: Float = 1.0f,
    val lastStateVersion: Long = 0
)

// Add Layer 2 data models
@Serializable
data class Layer2LocationPacket(
    val type: String = "location",
    val peerId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)

@Serializable
data class Layer2AnnotationPacket(
    val type: String = "annotation",
    val annotation: MapAnnotation
)

data class PacketSummary(
    val packetType: String,
    val peerId: String,
    val peerNickname: String?,
    val timestamp: Long
)