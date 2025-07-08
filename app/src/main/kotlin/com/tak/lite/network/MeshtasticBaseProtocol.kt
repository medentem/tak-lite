package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase
import com.geeksville.mesh.Portnums.PortNum
import com.google.protobuf.ByteString
import com.google.protobuf.kotlin.isNotEmpty
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.data.model.MeshtasticChannel
import com.tak.lite.data.model.MessageStatus
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.util.DeviceController
import com.tak.lite.util.MeshAnnotationInterop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.random.Random

abstract class MeshtasticBaseProtocol(
    private val context: Context,
    private val coroutineContext: CoroutineContext
) : MeshProtocol {
    private val TAG = "MeshtasticBaseProtocol"
    private val MESSAGE_TIMEOUT_MS = 30000L // 30 seconds timeout for messages
    private val PACKET_TIMEOUT_MS = 8000L // 8 seconds timeout for packet queue (closer to BLE timeout)
    private val ERROR_BYTE_STRING: ByteString = ByteString.copyFrom(ByteArray(32) { 0 })
    private val MAX_RETRY_COUNT = 1 // Maximum number of retries for failed messages
    private val LOCATION_REQUEST_TIMEOUT_MS = 60_000L // 60 seconds
    private val PKC_CHANNEL_INDEX = 8

    // Message tracking
    private var lastSentMessage: ChannelMessage? = null
    private var currentPacketId: Long = Random(System.currentTimeMillis()).nextLong().absoluteValue

    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    private var peerLocationCallback: ((Map<String, PeerLocationEntry>) -> Unit)? = null
    private var userLocationCallback: ((LatLng) -> Unit)? = null

    abstract fun sendPacket(packet: MeshProtos.MeshPacket): Boolean

    // Add location request tracking
    private data class LocationRequest(
        val peerId: String,
        val callback: (timeout: Boolean) -> Unit,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val pendingLocationRequests = ConcurrentHashMap<Int, LocationRequest>()

    private var selectedChannelId: String? = null
    private var selectedChannelIndex: Int = 0  // Default to primary channel (index 0)

    var connectedNodeId: String? = null
        internal set

    internal val inFlightMessages = ConcurrentHashMap<Int, MeshProtos.MeshPacket>()
    // Add message retry tracking
    private val messageRetryCount = ConcurrentHashMap<Int, Int>()

    internal val peerLocations = ConcurrentHashMap<String, PeerLocationEntry>()

    // Add queue management structures
    private val queuedPackets = ConcurrentLinkedQueue<MeshProtos.MeshPacket>()
    internal val queueResponse = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()
    private var queueJob: Job? = null
    private val queueScope = CoroutineScope(coroutineContext + SupervisorJob())

    internal val handshakeComplete = AtomicBoolean(false)

    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()

    private val _channels = MutableStateFlow<List<IChannel>>(emptyList())
    override val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()

    private val nodeInfoMap = ConcurrentHashMap<String, MeshProtos.NodeInfo>()
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    override val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    internal val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Disconnected)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    override val channelMessages: StateFlow<Map<String, List<ChannelMessage>>> = _channelMessages.asStateFlow()

    private val channelLastMessages = ConcurrentHashMap<String, ChannelMessage>()

    internal val _configDownloadStep = MutableStateFlow<ConfigDownloadStep>(ConfigDownloadStep.NotStarted)
    override val configDownloadStep: StateFlow<ConfigDownloadStep> = _configDownloadStep.asStateFlow()

    // Add counters for each config step
    internal val _configStepCounters = MutableStateFlow<Map<ConfigDownloadStep, Int>>(emptyMap())
    override val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>> = _configStepCounters.asStateFlow()

    override val requiresAppLocationSend: Boolean = false
    override val allowsChannelManagement: Boolean = false

    internal val _localNodeIdOrNickname = MutableStateFlow<String?>(null)
    override val localNodeIdOrNickname: StateFlow<String?>
        get() = _localNodeIdOrNickname.asStateFlow()

    // Add timeout job management
    internal class TimeoutJobManager {
        private val timeoutJobs = ConcurrentHashMap<Int, Job>()

        fun startTimeout(packet: MeshProtos.MeshPacket, timeoutMs: Long, onTimeout: (MeshProtos.MeshPacket) -> Unit) {
            val job = CoroutineScope(Dispatchers.IO).launch {
                try {
                    delay(timeoutMs)
                    onTimeout(packet)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // This is expected when timeout is cancelled (e.g., packet was acknowledged)
                    Log.d("TimeoutJobManager", "Timeout job cancelled for packet ${packet.id} (expected)")
                } catch (e: Exception) {
                    Log.e("TimeoutJobManager", "Error in timeout job for packet ${packet.id}", e)
                }
            }
            timeoutJobs[packet.id] = job
        }

        fun cancelTimeout(requestId: Int) {
            timeoutJobs.remove(requestId)?.cancel()
        }

        fun cancelAll() {
            timeoutJobs.values.forEach { it.cancel() }
            timeoutJobs.clear()
        }
    }

    internal val timeoutJobManager = TimeoutJobManager()

    /**
     * Generate a unique packet ID (if we know enough to do so - otherwise return 0 so the device will do it)
     */
    @Synchronized
    private fun generatePacketId(): Int {
        val numPacketIds = ((1L shl 32) - 1) // A mask for only the valid packet ID bits, either 255 or maxint

        currentPacketId++

        currentPacketId = currentPacketId and 0xffffffff // keep from exceeding 32 bits

        // Use modulus and +1 to ensure we skip 0 on any values we return
        return ((currentPacketId % numPacketIds) + 1L).toInt()
    }


    // Helper to determine if a peerId is our own node
    private fun isOwnNode(peerId: String): Boolean {
        return connectedNodeId != null && peerId == connectedNodeId
    }

    private fun handleTextMeshPacket(decoded: MeshProtos.Data, peerId: String, peerNickname: String?, packet: MeshProtos.MeshPacket) {
        try {
            Log.d(TAG, "Handling TEXT_MESSAGE_APP packet. peerId: $peerId, peerNickname: $peerNickname")
            val content = decoded.payload.toString(Charsets.UTF_8)

            // Check if this is a message from our own node
            if (isOwnNode(peerId)) {
                Log.d(TAG, "Ignoring message from our own node")
                return
            }

            // Use peerNickname if available, otherwise fall back to peerId
            val senderShortName = peerNickname ?: peerId

            // Check if this is a message we sent (by checking requestId)
            val requestId = decoded.requestId
            val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
            if (unsignedRequestId != 0) {  // 0 means no requestId
                val existingPacket = inFlightMessages[unsignedRequestId]
                if (existingPacket != null) {
                    // This is a message we sent, don't add it again
                    Log.d(TAG, "Received our own message back with requestId $requestId, not adding to channel messages")
                    return
                }
            }

            // Determine if this is a direct message by checking the "to" field
            val toId = (packet.to.toLong() and 0xFFFFFFFFL)
            val isDirectMessage = toId.toString() == connectedNodeId
            val channelId = if (isDirectMessage) {
                // For direct messages, get or create a direct message channel
                val directChannel = getOrCreateDirectMessageChannel(peerId)
                directChannel?.id
            } else {
                // For broadcast messages, use the channel from the packet
                val channelIndex = packet.channel
                val channel = _channels.value.find { it.index == channelIndex }
                if (channel == null) {
                    Log.e(TAG, "Could not find channel with index $channelIndex")
                    return
                }
                channel.id
            }

            val message = ChannelMessage(
                senderShortName = senderShortName,
                content = content,
                timestamp = System.currentTimeMillis(),
                channelId = channelId ?: "",
                status = MessageStatus.RECEIVED,
                requestId = unsignedRequestId,
                senderId = peerId
            )
            Log.d(TAG, "Created message object: sender=$senderShortName, content=$content, isDirect=$isDirectMessage")

            // Update channel messages
            val currentMessages = _channelMessages.value.toMutableMap()
            val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
            channelMessages.add(message)
            currentMessages[channelId ?: ""] = channelMessages
            _channelMessages.value = currentMessages
            Log.d(TAG, "Updated channel messages, new count: ${channelMessages.size}")

            // Update the channel's last message
            channelLastMessages[channelId ?: ""] = message

            // Update the channel in the list with the new message
            val currentChannels = _channels.value.toMutableList()
            val currentChannelsIndex = currentChannels.indexOfFirst { it.id == channelId }
            if (currentChannelsIndex != -1) {
                val channel = currentChannels[currentChannelsIndex]
                currentChannels[currentChannelsIndex] = when (channel) {
                    is MeshtasticChannel -> channel.copy(lastMessage = message)
                    is DirectMessageChannel -> channel.copy(lastMessage = message)
                    else -> throw IllegalArgumentException("Unknown channel type")
                }
                _channels.value = currentChannels
                Log.d(TAG, "Updated channel with new last message")
            }

            Log.d(TAG, "Successfully processed text message from $senderShortName: $content")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse text message: ${e.message}", e)
        }
    }

    internal fun handleNodeInfo(nodeInfo: MeshProtos.NodeInfo){
        val nodeNum = (nodeInfo.num.toLong() and 0xFFFFFFFFL).toString()

        // Get existing node info if any
        val existingNode = nodeInfoMap[nodeNum]

        // Check for public key match
        val keyMatch = existingNode?.user?.publicKey?.let { existingKey ->
            !existingKey.isNotEmpty() || existingKey == nodeInfo.user.publicKey
        } ?: true

        // Update node info with proper key handling
        val updatedNodeInfo = if (keyMatch) {
            nodeInfo
        } else {
            Log.w(TAG, "Public key mismatch from ${nodeInfo.user.longName} (${nodeInfo.user.shortName})")
            nodeInfo.toBuilder()
                .setUser(nodeInfo.user.toBuilder()
                    .setPublicKey(ERROR_BYTE_STRING)
                    .build())
                .build()
        }

        // Update node info map
        nodeInfoMap[nodeNum] = updatedNodeInfo

        // Update peers collection
        val peerName = updatedNodeInfo.user.shortName.takeIf { it.isNotBlank() }
            ?: updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
            ?: nodeNum

        // Update peers collection
        val longName = updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
            ?: nodeNum

        peersMap[nodeNum] = MeshPeer(
            id = nodeNum,
            ipAddress = "N/A",
            lastSeen = updatedNodeInfo.lastHeard * 1000L, // Convert from seconds to milliseconds
            nickname = peerName,
            longName = longName,
            hasPKC = updatedNodeInfo.user.publicKey.isNotEmpty() &&
                    updatedNodeInfo.user.publicKey != ERROR_BYTE_STRING
        )
        _peers.value = peersMap.values.toList()
        Log.d(TAG, "Updated peer marker for $nodeNum with name $peerName")

        addPacketSummary(PayloadVariantCase.NODE_INFO, PortNum.NODEINFO_APP, nodeNum, peerName)

        // Process position data if available in NodeInfo
        if (updatedNodeInfo.hasPosition()) {
            processPositionData(updatedNodeInfo.position, nodeNum, "NodeInfo")
        }

        // Update direct message channel if it exists
        val channelId = DirectMessageChannel.createId(nodeNum)
        val existingChannel = _channels.value.find { it.id == channelId }
        if (existingChannel is DirectMessageChannel) {
            // Get updated name from node info
            val channelName = updatedNodeInfo.user.longName.takeIf { it.isNotBlank() }
                ?: updatedNodeInfo.user.shortName.takeIf { it.isNotBlank() }
                ?: nodeNum

            // Check if user is unmessageable
            val isUnmessageable = if (updatedNodeInfo.user.hasIsUnmessagable()) {
                updatedNodeInfo.user.isUnmessagable
            } else {
                false
            }

            // Create updated channel with new name, PKI status, and readyToSend status
            val updatedChannel = existingChannel.copy(
                name = channelName,
                isPkiEncrypted = updatedNodeInfo.user.publicKey.isNotEmpty() &&
                        updatedNodeInfo.user.publicKey != ERROR_BYTE_STRING,
                readyToSend = !isUnmessageable
            )

            // Update in channels collection
            val currentChannels = _channels.value.toMutableList()
            val channelIndex = currentChannels.indexOfFirst { it.id == channelId }
            if (channelIndex != -1) {
                currentChannels[channelIndex] = updatedChannel
                _channels.value = currentChannels
                Log.d(TAG, "Updated direct message channel for node $nodeNum with new name: $channelName, readyToSend: ${updatedChannel.readyToSend}")
            }
        }
    }

    /**
     * Shared function to process position data from either POSITION_APP packets or NodeInfo packets
     * @param position The position data to process
     * @param peerId The peer ID this position belongs to
     * @param source The source of the position data (e.g., "POSITION_APP", "NodeInfo")
     */
    private fun processPositionData(position: MeshProtos.Position, peerId: String, source: String) {
        try {
            val lat = position.latitudeI / 1e7
            val lng = position.longitudeI / 1e7
            Log.d(TAG, "Parsed position from $source for peer $peerId: lat=$lat, lng=$lng")

            // Create enhanced location entry with additional position data
            val locationEntry = PeerLocationEntry(
                timestamp = System.currentTimeMillis(),
                latitude = lat,
                longitude = lng,
                gpsTimestamp = position.time * 1000L, // Convert seconds to milliseconds
                groundSpeed = if (position.hasGroundSpeed()) position.groundSpeed.toDouble() / 3.6 else null, // Convert km/h to m/s
                groundTrack = if (position.hasGroundTrack()) position.groundTrack * 1e-5 else null,
                altitude = if (position.hasAltitude()) position.altitude else null,
                altitudeHae = if (position.hasAltitudeHae()) position.altitudeHae else null,
                gpsAccuracy = position.gpsAccuracy, // Required field
                fixQuality = position.fixQuality, // Required field
                fixType = position.fixType, // Required field
                satellitesInView = position.satsInView, // Required field
                pdop = position.pdop, // Required field
                hdop = position.hdop, // Required field
                vdop = position.vdop, // Required field
                locationSource = position.locationSource.number, // Required field
                altitudeSource = position.altitudeSource.number, // Required field
                sequenceNumber = position.seqNumber, // Required field
                precisionBits = position.precisionBits // Required field
            )

            // Log additional position data if available
            if (locationEntry.hasVelocityData()) {
                val (speed, track) = locationEntry.getVelocity()!!
                Log.d(TAG, "Position from $source for peer $peerId includes velocity: ${speed.toInt()} m/s at ${track.toInt()}°")
            }
            if (locationEntry.hasGpsQualityData()) {
                Log.d(TAG, "Position from $source for peer $peerId includes GPS quality: accuracy=${locationEntry.gpsAccuracy}mm, fix=${locationEntry.fixType}, sats=${locationEntry.satellitesInView}")
            }

            peerLocations[peerId] = locationEntry

            Log.d(TAG, "Updated peer location from $source for $peerId: ${locationEntry.latitude}, ${locationEntry.longitude}")
            Log.d(TAG, "Total peer locations now: ${peerLocations.size}, peer IDs: ${peerLocations.keys}")

            // Call enhanced callback with full location entry data
            peerLocationCallback?.invoke(peerLocations.toMap())

            // Update last seen for node info
            nodeInfoMap[peerId]?.let { info ->
                val updatedNodeInfo = info.toBuilder().setLastHeard((System.currentTimeMillis() / 1000).toInt()).build()
                nodeInfoMap[peerId] = updatedNodeInfo
            }

            if (isOwnNode(peerId)) {
                userLocationCallback?.invoke(LatLng(lat, lng))
            }

            // Check if this is a response to a location request
            // Find any pending location requests for this peer
            val pendingRequest = pendingLocationRequests.entries.find { (_, request) ->
                request.peerId == peerId
            }
            if (pendingRequest != null) {
                Log.d(TAG, "Received location response from peer $peerId")
                pendingRequest.value.callback(false) // Call with timeout = false
                pendingLocationRequests.remove(pendingRequest.key)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse position from $source for peer $peerId: ${e.message}", e)
        }
    }

    private fun addPacketSummary(payloadVariantCase: PayloadVariantCase, portNum: PortNum, peerId: String, peerNickname: String?) {
        val packetTypeString = when (payloadVariantCase) {
            PayloadVariantCase.PACKET -> {
                when (portNum) {
                    PortNum.POSITION_APP -> "Position Update"
                    PortNum.ATAK_PLUGIN -> "Annotation"
                    PortNum.ROUTING_APP -> "Routing"
                    else -> "Packet (Portnum: $portNum)"
                }
            }
            PayloadVariantCase.CONFIG -> "Config"
            PayloadVariantCase.MODULECONFIG -> "Module Config"
            PayloadVariantCase.CHANNEL -> "Channel"
            PayloadVariantCase.NODE_INFO -> "Node Info"
            PayloadVariantCase.MY_INFO -> "My Info"
            PayloadVariantCase.CONFIG_COMPLETE_ID -> "Config Complete"
            else -> payloadVariantCase.name
        }
        val summary = PacketSummary(
            packetType = packetTypeString,
            peerId = peerId,
            peerNickname = peerNickname,
            timestamp = System.currentTimeMillis()
        )
        val updated = (_packetSummaries.value + summary).takeLast(3)
        _packetSummaries.value = updated
    }

    internal fun handleMyInfo(myInfo: MeshProtos.MyNodeInfo) {
        Log.d(TAG, "=== Base Protocol handleMyInfo() Debug ===")
        Log.d(TAG, "Input MyNodeInfo details:")
        Log.d(TAG, "  - myNodeNum: ${myInfo.myNodeNum}")
        Log.d(TAG, "  - minAppVersion: ${myInfo.minAppVersion}")
        Log.d(TAG, "  - myNodeNum: ${myInfo.myNodeNum}")
        Log.d(TAG, "  - minAppVersion: ${myInfo.minAppVersion}")
        
        val rawNodeNum = myInfo.myNodeNum
        val unsignedNodeNum = (rawNodeNum.toLong() and 0xFFFFFFFFL)
        connectedNodeId = unsignedNodeNum.toString()
        
        Log.d(TAG, "Processing results:")
        Log.d(TAG, "  - Raw myNodeNum: $rawNodeNum")
        Log.d(TAG, "  - Unsigned conversion: $unsignedNodeNum")
        Log.d(TAG, "  - Final connectedNodeId: $connectedNodeId")
        Log.d(TAG, "  - Previous _localNodeIdOrNickname: ${_localNodeIdOrNickname.value}")
        
        _localNodeIdOrNickname.value = connectedNodeId
        
        Log.d(TAG, "  - Updated _localNodeIdOrNickname: ${_localNodeIdOrNickname.value}")
        Log.d(TAG, "=== Base Protocol handleMyInfo() Complete ===")
    }

    internal fun handleChannelUpdate(channel: com.geeksville.mesh.ChannelProtos.Channel) {
        // Ignore a non-primary channel without a name. Primary channel without a name is just LongFast
        if (channel.settings.name.isNullOrEmpty() && channel.role != com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY) {
            Log.d(TAG, "Ignoring channel update with null or empty name")
            return
        }
        Log.d(TAG, "Received channel update from device: id=${channel.settings.id}, name=${channel.settings.name}, role=${channel.role}")
        val channelId = "${channel.index}_${channel.settings.name}"

        val meshtasticChannel = MeshtasticChannel(
            id = channelId,
            name = channel.settings.name,
            displayName = if ((channel.settings.name.isNullOrEmpty() && channel.role == com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY) || channel.settings.name == "LongFast") {
                "Default (Public)"
            } else {
                channel.settings.name
            },
            index = channel.index,
            isDefault = channel.index == 0,
            members = emptyList(), // TODO: Track members based on node info
            role = when (channel.role) {
                com.geeksville.mesh.ChannelProtos.Channel.Role.PRIMARY -> MeshtasticChannel.ChannelRole.PRIMARY
                com.geeksville.mesh.ChannelProtos.Channel.Role.SECONDARY -> MeshtasticChannel.ChannelRole.SECONDARY
                else -> MeshtasticChannel.ChannelRole.DISABLED
            },
            psk = channel.settings.psk.toByteArray(),
            uplinkEnabled = channel.settings.uplinkEnabled,
            downlinkEnabled = channel.settings.downlinkEnabled,
            positionPrecision = channel.settings.moduleSettings.positionPrecision,
            isClientMuted = channel.settings.moduleSettings.isClientMuted,
            lastMessage = channelLastMessages[channelId]
        )

        Log.d(TAG, "Created MeshtasticChannel: ${meshtasticChannel.name} (${meshtasticChannel.id})")
        Log.d(TAG, "Current channels before update: ${_channels.value.map { "${it.name} (${it.id})" }}")

        val currentChannels = _channels.value.toMutableList()
        val existingIndex = currentChannels.indexOfFirst { it.id == channelId }
        if (existingIndex != -1) {
            currentChannels[existingIndex] = meshtasticChannel
        } else {
            currentChannels.add(meshtasticChannel)
        }
        _channels.value = currentChannels
        Log.d(TAG, "Current channels after update: ${_channels.value.map { "${it.name} (${it.id})" }}")
        Log.d(TAG, "Channel flow has ${_channels.value.size} channels: ${_channels.value.map { "${it.name} (${it.id})" }}")

        // If this is the first channel (index 0) and no channel is selected, select it
        if (channel.index == 0 && selectedChannelId.isNullOrEmpty()) {
            selectedChannelId = meshtasticChannel.id
            Log.d(TAG, "Set default channel to: ${meshtasticChannel.name}")
        }
    }

    internal fun handleRoutingPacket(routing: MeshProtos.Routing, fromId: String, requestId: Int) {
        // Check if this is a response to one of our messages
        val packet = inFlightMessages.remove(requestId)
        if (packet != null) {
            // Cancel the timeout job since we got a response
            timeoutJobManager.cancelTimeout(packet.id)
            messageRetryCount.remove(requestId)

            val isAck = routing.errorReason == MeshProtos.Routing.Error.NONE
            val packetTo = (packet.to.toLong() and 0xFFFFFFFFL).toString()
            Log.d(TAG, "Routing response for requestId $requestId - fromId: $fromId, packet originally to: $packetTo, errorReason: ${routing.errorReason} (code: ${routing.errorReason.number})")
            val newStatus = when {
                isAck && fromId == packetTo -> MessageStatus.RECEIVED
                isAck -> MessageStatus.DELIVERED
                else -> MessageStatus.FAILED
            }
            updateMessageStatusForPacket(packet, newStatus)
            Log.d(TAG, "Updated packet ${packet.id} status to $newStatus (fromId: $fromId, isAck: $isAck)")

            // Complete the queue response if it exists
            queueResponse.remove(packet.id)?.complete(isAck)
            Log.d(TAG, "Completed queue response for requestId $requestId with success=$isAck")
        }
    }

    internal fun handlePacket(meshPacket: MeshProtos.MeshPacket, payloadVariantCase: PayloadVariantCase) {
        Log.d(TAG, "Parsed MeshPacket: $meshPacket")
        if (meshPacket.hasDecoded()) {
            val decoded = meshPacket.decoded
            // Convert node IDs to unsigned for consistent handling
            val peerId = (meshPacket.from.toLong() and 0xFFFFFFFFL).toString()
            Log.d(TAG, "Decoded payload from peer $peerId: $decoded")

            // Check if peer already exists
            val existingPeer = peersMap[peerId]
            if (existingPeer != null) {
                // Update lastSeen for existing peer
                peersMap[peerId] = existingPeer.copy(lastSeen = System.currentTimeMillis())
                Log.d(TAG, "Updated lastSeen for existing peer $peerId")
            } else {
                // Create new peer for first-time contact
                peersMap[peerId] = MeshPeer(
                    id = peerId,
                    ipAddress = "N/A",
                    lastSeen = System.currentTimeMillis(),
                    nickname = null
                )
                Log.d(TAG, "Created new peer marker for $peerId")
            }
            _peers.value = peersMap.values.toList()
            Log.d(TAG, "Updated peer marker for $peerId")
            // Add to packet summary flow
            val peerNickname = nodeInfoMap[peerId]?.user?.shortName
                ?.takeIf { it.isNotBlank() }
                ?: nodeInfoMap[peerId]?.user?.longName
                ?: peersMap[peerId]?.nickname

            addPacketSummary(payloadVariantCase, decoded.portnum, peerId, peerNickname)

            // Handle routing packets for message status updates
            if (decoded.portnum == PortNum.ROUTING_APP) {
                try {
                    val routing = MeshProtos.Routing.parseFrom(decoded.payload)
                    // Get requestId from the decoded data and convert to unsigned
                    val requestId = decoded.requestId
                    val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
                    handleRoutingPacket(routing, peerId, unsignedRequestId)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse routing packet: ${e.message}")
                }
            } else if (decoded.portnum == PortNum.TEXT_MESSAGE_APP) {
                handleTextMeshPacket(decoded, peerId, peerNickname, meshPacket)
            } else if (decoded.portnum == PortNum.POSITION_APP) {
                try {
                    val position = MeshProtos.Position.parseFrom(decoded.payload)
                    processPositionData(position, peerId, "POSITION_APP")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse POSITION_APP payload: ${e.message}", e)
                }
            } else if (decoded.portnum == PortNum.ATAK_PLUGIN) {
                // Try to parse as bulk deletion
                val bulkDeleteIds = MeshAnnotationInterop.meshDataToBulkDeleteIds(decoded)
                if (bulkDeleteIds != null) {
                    Log.d(TAG, "Parsed bulk deletion of ${bulkDeleteIds.size} IDs from peer $peerId")
                    // Remove all matching annotations
                    _annotations.value = _annotations.value.filter { it.id !in bulkDeleteIds }
                    annotationCallback?.let { cb ->
                        bulkDeleteIds.forEach { id ->
                            cb(MapAnnotation.Deletion(id = id, creatorId = peerId))
                        }
                    }
                } else {
                    val annotation = MeshAnnotationInterop.meshDataToMapAnnotation(decoded)
                    if (annotation != null) {
                        Log.d(TAG, "Parsed annotation from peer $peerId: $annotation")
                        annotationCallback?.invoke(annotation)
                        // Replace or remove annotation by ID
                        when (annotation) {
                            is MapAnnotation.Deletion -> {
                                _annotations.value = _annotations.value.filter { it.id != annotation.id }
                            }
                            else -> {
                                // Replace if exists, add if new, keep most recent by timestamp
                                val existing = _annotations.value.find { it.id == annotation.id }
                                if (existing == null || annotation.timestamp > existing.timestamp) {
                                    _annotations.value = _annotations.value.filter { it.id != annotation.id } + annotation
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Received ATAK_PLUGIN message from $peerId but failed to parse annotation")
                    }
                }
            } else {
                Log.d(TAG, "Ignored packet from $peerId with portnum: ${decoded.portnum}")
            }
        } else {
            Log.d(TAG, "MeshPacket has no decoded payload")
        }
    }

    internal fun handleQueueStatus(queueStatus: MeshProtos.QueueStatus) {
        Log.d(TAG, "Received QueueStatus: ${queueStatus}")
        val (success, isFull, meshPacketId) = with(queueStatus) {
            Triple(res == 0, free == 0, meshPacketId)
        }

        if (success && isFull) {
            Log.d(TAG, "Queue is full, waiting for free space")
            return
        }

        // Convert meshPacketId to unsigned for consistent handling
        val unsignedMeshPacketId = meshPacketId.toLong() and 0xFFFFFFFFL
        Log.d(TAG, "QueueStatus - success: $success, isFull: $isFull, meshPacketId: $meshPacketId (unsigned: $unsignedMeshPacketId)")

        // Complete the future for this packet
        if (meshPacketId != 0) {
            queueResponse.remove(meshPacketId)?.complete(success)

            // Update message status if this was a text message
            val packet = inFlightMessages[unsignedMeshPacketId.toInt()]
            if (packet != null) {
                val newStatus = if (success) MessageStatus.SENT else MessageStatus.ERROR
                updateMessageStatusForPacket(packet, newStatus)
            }
        } else {
            // If no specific packet ID, complete the last pending response
            queueResponse.entries.lastOrNull { !it.value.isCompleted }?.value?.complete(success)
        }
    }

    internal fun updateMessageStatusForPacket(packet: MeshProtos.MeshPacket, newStatus: MessageStatus) {
        if (!packet.hasDecoded() || packet.decoded.portnum != PortNum.TEXT_MESSAGE_APP) {
            return // Only update status for text messages
        }

        val requestId = packet.id
        val unsignedRequestId = (requestId.toLong() and 0xFFFFFFFFL).toInt()
        val toId = (packet.to.toLong() and 0xFFFFFFFFL)
        val channelId = if (toId != 0xFFFFFFFFL) {
            // This is a direct message
            Log.d(TAG, "Direct message status update: ${packet.channel}")
            DirectMessageChannel.createId(toId.toString())
        } else {
            // This is a channel message
            val channel = _channels.value.find { it.index == packet.channel }
            if (channel == null) {
                Log.e(TAG, "Could not find channel with index ${packet.channel}")
                return
            }
            "${channel.index}_${channel.name}"
        }

        val currentMessages = _channelMessages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        val messageIndex = channelMessages.indexOfFirst { it.requestId == unsignedRequestId }

        if (messageIndex != -1) {
            val currentMessage = channelMessages[messageIndex]
            val updatedMessage = currentMessage.copyWithStatus(newStatus)
            
            if (updatedMessage != null) {
                channelMessages[messageIndex] = updatedMessage
                currentMessages[channelId] = channelMessages
                _channelMessages.value = currentMessages
                Log.d(TAG, "Updated message ${unsignedRequestId} status from ${currentMessage.status} to ${newStatus}")
            } else {
                Log.w(TAG, "Rejected status update for message ${unsignedRequestId}: cannot transition from ${currentMessage.status} to ${newStatus}")
            }
        } else {
            Log.e(TAG, "Could not find message with id $unsignedRequestId in channel $channelId")
        }
    }
    /**
     * Queue a packet for sending. This is the main entry point for all packet queuing.
     * @param packet The MeshPacket to queue
     */
    internal fun queuePacket(packet: MeshProtos.MeshPacket) {
        Log.d(TAG, "Queueing packet id=${packet.id}, type=${packet.decoded.portnum}")
        queuedPackets.offer(packet)
        startPacketQueue()
    }

    internal fun startPacketQueue() {
        if (queueJob?.isActive == true) return
        if (!handshakeComplete.get()) {
            Log.d(TAG, "Not starting packet queue - waiting for handshake completion")
            return
        }
        queueJob = queueScope.launch {
            Log.d(TAG, "Packet queue job started")
            while (true) {
                try {
                    // Take the first packet from the queue
                    val packet = queuedPackets.poll() ?: break
                    Log.d(TAG, "Processing queued packet id=${packet.id}")

                    // Send packet to radio and wait for response
                    val future = CompletableDeferred<Boolean>()
                    queueResponse[packet.id] = future

                    try {
                        sendPacket(packet)
                        withTimeout(PACKET_TIMEOUT_MS) {
                            val success = future.await()
                            Log.d(TAG, "Packet id=${packet.id} processed with success=$success")
                            if (!success) {
                                // If packet failed in queue, don't wait for message timeout
                                if (packet.hasDecoded() && packet.decoded.portnum == PortNum.TEXT_MESSAGE_APP) {
                                    timeoutJobManager.cancelTimeout(packet.id)
                                    updateMessageStatusForPacket(packet, MessageStatus.FAILED)
                                }
                            }
                        }
                    } catch (e: TimeoutCancellationException) {
                        Log.w(TAG, "Packet id=${packet.id} timed out in queue")
                        queueResponse.remove(packet.id)?.complete(false)
                        // Don't update message status here - let message timeout handle it
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing packet id=${packet.id}", e)
                        queueResponse.remove(packet.id)?.complete(false)
                        // Don't update message status here - let message timeout handle it
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in packet queue job", e)
                }
            }
        }
    }

    override suspend fun selectChannel(channelId: String) {
        Log.d(TAG, "Selecting channel: $channelId")
        selectedChannelId = channelId

        // Find the channel and set its index
        val channel = _channels.value.find { it.id == channelId }
        if (channel != null) {
            selectedChannelIndex = channel.index
            Log.d(TAG, "Set selected channel index to: $selectedChannelIndex")
        } else {
            Log.w(TAG, "Could not find channel with ID: $channelId, defaulting to index 0")
            // fallback to the first channel
            selectedChannelIndex = 0
        }
    }

    override fun getPeerName(peerId: String): String? {
        val nodeInfo = getNodeInfoForPeer(peerId)
        return nodeInfo?.user?.shortName ?: nodeInfo?.user?.longName ?: peerId
    }

    override fun getPeerLastHeard(peerId: String): Long? {
        val nodeInfo = getNodeInfoForPeer(peerId)
        return nodeInfo?.lastHeard?.toLong()
    }

    /**
     * Send a bulk deletion of annotation IDs as a single packet, batching as many as will fit under 252 bytes.
     */
    override fun sendBulkAnnotationDeletions(ids: List<String>) {
        if (ids.isEmpty()) return
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        // Batch IDs into groups that fit under 252 bytes
        var batch = mutableListOf<String>()
        for (id in ids) {
            val testBatch = batch + id
            val data = MeshAnnotationInterop.bulkDeleteToMeshData(testBatch, nickname, battery)
            val size = data.toByteArray().size
            if (size > 252 && batch.isNotEmpty()) {
                // Send current batch
                val packet = MeshProtos.MeshPacket.newBuilder()
                    .setTo(0xffffffffL.toInt())
                    .setDecoded(MeshAnnotationInterop.bulkDeleteToMeshData(batch, nickname, battery))
                    .setChannel(selectedChannelIndex)
                    .setId(generatePacketId())
                    .build()
                queuePacket(packet)
                batch = mutableListOf(id)
            } else if (size > 252) {
                // Single ID is too large (should not happen), skip
                continue
            } else {
                batch.add(id)
            }
        }
        if (batch.isNotEmpty()) {
            val packet = MeshProtos.MeshPacket.newBuilder()
                .setTo(0xffffffffL.toInt())
                .setDecoded(MeshAnnotationInterop.bulkDeleteToMeshData(batch, nickname, battery))
                .setChannel(selectedChannelIndex)
                .setId(generatePacketId())
                .build()
            queuePacket(packet)
        }
    }

    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    override fun setPeerLocationCallback(callback: (Map<String, PeerLocationEntry>) -> Unit) {
        peerLocationCallback = callback
    }

    override fun sendAudioData(audioData: ByteArray, channelId: String) {
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Cannot send audio data: Channel $channelId not found")
            return
        }

        // TODO: Implement audio data sending using Meshtastic protocol
        // This will need to use the appropriate port number and encryption based on the channel settings
    }

    override fun setLocalNickname(nickname: String) {
        TODO("Not yet implemented")
    }

    override fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, PeerLocationEntry>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // noop - we don't do full state sync in meshtastic
        return
    }

    override fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        userLocationCallback = callback
    }


    override fun requestPeerLocation(
        peerId: String,
        onPeerLocationReceived: (timeout: Boolean) -> Unit
    ) {
        Log.d(TAG, "Sending location request to peer $peerId")
        // Get the peer's node info to check PKI status
        val peerNodeInfo = nodeInfoMap[peerId]
        if (peerNodeInfo == null) {
            Log.e(TAG, "Cannot send direct message: No node info for peer $peerId")
            return
        }

        // Convert peerId to unsigned int for the packet
        val peerIdInt = peerId.toLong().toInt() // This preserves the unsigned value
        // Create the mesh packet using the new helper method
        val packet = newMeshPacketTo(peerIdInt).buildMeshPacket(
            channel = peerNodeInfo.channel,
            priority = MeshProtos.MeshPacket.Priority.BACKGROUND
        ) {
            portnum = PortNum.POSITION_APP
            wantResponse = true
        }

        // Store the callback with the packet ID
        pendingLocationRequests[packet.id] = LocationRequest(peerId, onPeerLocationReceived)

        // Queue the packet
        queuePacket(packet)

        // Start a timeout coroutine
        CoroutineScope(coroutineContext).launch {
            delay(LOCATION_REQUEST_TIMEOUT_MS)
            // Check if the request is still pending
            val request = pendingLocationRequests[packet.id]
            if (request != null) {
                Log.d(TAG, "Location request to peer $peerId timed out")
                request.callback(true) // Call with timeout = true
                pendingLocationRequests.remove(packet.id)
            }
        }
    }

    override fun sendLocationUpdate(latitude: Double, longitude: Double) {
        // Only send location data, not an annotation
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        // Build a location-only packet (customize as needed for your protocol)
        val data = MeshAnnotationInterop.mapLocationToMeshData(
            nickname = nickname,
            batteryLevel = battery,
            pliLatitude = latitude,
            pliLongitude = longitude
        )
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .setChannel(selectedChannelIndex)
            .setId(generatePacketId())
            .build()
        queuePacket(packet)
    }

    override fun sendAnnotation(annotation: MapAnnotation) {
        Log.d(TAG, "Preparing to send annotation: $annotation")
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        val data = MeshAnnotationInterop.mapAnnotationToMeshData(
            annotation = annotation,
            nickname = nickname,
            batteryLevel = battery
        )
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .setChannel(selectedChannelIndex)
            .setId(generatePacketId())
            .build()
        Log.d(TAG, "Sending annotation: $annotation as packet bytes: "+
                packet.toByteArray().joinToString(", ", limit = 16))
        queuePacket(packet)
    }


    // Add helper methods for building mesh packets
    private fun newMeshPacketTo(to: Int = 0xffffffffL.toInt()): MeshProtos.MeshPacket.Builder {
        return MeshProtos.MeshPacket.newBuilder()
            .setTo(to)
    }

    private fun MeshProtos.MeshPacket.Builder.buildMeshPacket(
        wantAck: Boolean = false,
        id: Int = generatePacketId(),
        hopLimit: Int = 7, // Default hop limit
        channel: Int = 0,
        priority: MeshProtos.MeshPacket.Priority = MeshProtos.MeshPacket.Priority.UNSET,
        initFn: MeshProtos.Data.Builder.() -> Unit
    ): MeshProtos.MeshPacket {
        this.wantAck = wantAck
        this.id = id
        this.hopLimit = hopLimit
        this.priority = priority

        // Always set the channel field first
        this.channel = channel

        // Set PKI encryption and public key before building if using PKC channel
        if (channel == PKC_CHANNEL_INDEX) {
            this.pkiEncrypted = true
            // Get the target node's public key from nodeInfoMap
            val targetNodeId = this.to.toString()
            nodeInfoMap[targetNodeId]?.user?.publicKey?.let { publicKey ->
                this.publicKey = publicKey
            }
        }

        decoded = MeshProtos.Data.newBuilder().also {
            initFn(it)
        }.build()
        return build()
    }

    override fun sendTextMessage(channelId: String, content: String) {
        // Get the channel index from the channel ID
        val channelIndex = channelId.split("_").firstOrNull()?.toIntOrNull()
        if (channelIndex == null) {
            Log.e(TAG, "Invalid channel ID format: $channelId")
            return
        }

        val textMessagePacketId = generatePacketId()

        // Create the mesh packet using the new helper method
        val packet = newMeshPacketTo().buildMeshPacket(
            id = textMessagePacketId,
            wantAck = true,
            channel = channelIndex,
            priority = MeshProtos.MeshPacket.Priority.RELIABLE
        ) {
            portnum = PortNum.TEXT_MESSAGE_APP
            payload = ByteString.copyFromUtf8(content)
            requestId = textMessagePacketId
        }

        // Get the sender's short name from node info
        val senderShortName = getNodeInfoForPeer(connectedNodeId ?: "")?.user?.shortName
            ?: connectedNodeId ?: "Unknown"

        // Create the message object for UI
        val message = ChannelMessage(
            senderShortName = senderShortName,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            channelId = channelId,
            requestId = textMessagePacketId,
            senderId = connectedNodeId ?: "0"
        )

        // Store this message as the last sent message
        lastSentMessage = message
        inFlightMessages[textMessagePacketId] = packet

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Start message timeout
        startMessageTimeout(packet)

        // Queue the packet
        queuePacket(packet)
    }

    override fun getChannelName(channelId: String): String? {
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Can't set notification info: Channel $channelId not found")
            return "Messages"
        }
        return channel.name
    }

    override fun sendDirectMessage(peerId: String, content: String) {
        Log.d(TAG, "Sending direct message to peer $peerId")
        // Get the peer's node info to check PKI status
        val peerNodeInfo = nodeInfoMap[peerId]
        if (peerNodeInfo == null) {
            Log.e(TAG, "Cannot send direct message: No node info for peer $peerId")
            return
        }

        // Check if both nodes have PKI capability
        val hasPKC = peerNodeInfo.user.publicKey.isNotEmpty() &&
                peerNodeInfo.user.publicKey != ERROR_BYTE_STRING
        Log.d(TAG, "Peer PKC status: $hasPKC")

        // Determine which channel to use
        val channelIndex = if (hasPKC) {
            PKC_CHANNEL_INDEX
        } else {
            // Fallback to the peer's channel if no PKI or mismatch
            peerNodeInfo.channel
        }
        Log.d(TAG, "Using channel index: $channelIndex")

        val textMessagePacketId = generatePacketId()
        Log.d(TAG, "Generated packet ID: $textMessagePacketId")

        // Convert peerId to unsigned int for the packet
        val peerIdInt = peerId.toLong().toInt() // This preserves the unsigned value
        Log.d(TAG, "Converted peer ID $peerId to int: $peerIdInt")

        // Create the mesh packet
        val packet = newMeshPacketTo(peerIdInt).buildMeshPacket(
            id = textMessagePacketId,
            wantAck = true,
            channel = channelIndex,
            priority = MeshProtos.MeshPacket.Priority.RELIABLE
        ) {
            portnum = PortNum.TEXT_MESSAGE_APP
            payload = ByteString.copyFromUtf8(content)
            requestId = textMessagePacketId
        }

        // Get the sender's short name from node info
        val senderShortName = getNodeInfoForPeer(connectedNodeId ?: "")?.user?.shortName
            ?: connectedNodeId ?: "Unknown"

        // Create the message object
        val message = ChannelMessage(
            senderShortName = senderShortName,
            content = content,
            timestamp = System.currentTimeMillis(),
            status = MessageStatus.SENDING,
            channelId = DirectMessageChannel.createId(peerId),
            requestId = textMessagePacketId,
            senderId = connectedNodeId ?: "0"
        )

        // Store this message as the last sent message
        lastSentMessage = message
        inFlightMessages[textMessagePacketId] = packet

        // Add to channel messages immediately
        val currentMessages = _channelMessages.value.toMutableMap()
        val channelId = DirectMessageChannel.createId(peerId)
        val channelMessages = currentMessages[channelId]?.toMutableList() ?: mutableListOf()
        channelMessages.add(message)
        currentMessages[channelId] = channelMessages
        _channelMessages.value = currentMessages

        // Start message timeout
        startMessageTimeout(packet)

        // Queue the packet
        queuePacket(packet)
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel? {
        val channelId = DirectMessageChannel.createId(peerId)
        val nodeInfo = nodeInfoMap[peerId]
        val latestName = nodeInfo?.user?.longName?.takeIf { it.isNotBlank() }
            ?: nodeInfo?.user?.shortName?.takeIf { it.isNotBlank() }
            ?: peerId
        val latestPki = nodeInfo?.user?.publicKey?.isNotEmpty() == true && nodeInfo.user?.publicKey != ERROR_BYTE_STRING

        // Check if user is unmessageable
        val isUnmessageable = nodeInfo?.user?.let { user ->
            if (user.hasIsUnmessagable()) {
                user.isUnmessagable
            } else {
                false
            }
        } ?: false
        val latestReadyToSend = !isUnmessageable

        // Check if channel already exists
        val existingChannel = _channels.value.find { it.id == channelId }
        if (existingChannel != null && existingChannel is DirectMessageChannel) {
            // If name, PKI status, or readyToSend status has changed, update the channel
            if (existingChannel.name != latestName || existingChannel.isPkiEncrypted != latestPki || existingChannel.readyToSend != latestReadyToSend) {
                val updatedChannel = existingChannel.copy(
                    name = latestName,
                    isPkiEncrypted = latestPki,
                    readyToSend = latestReadyToSend
                )
                val currentChannels = _channels.value.toMutableList()
                val idx = currentChannels.indexOfFirst { it.id == channelId }
                if (idx != -1) {
                    currentChannels[idx] = updatedChannel
                    _channels.value = currentChannels
                    Log.d(TAG, "Updated existing direct message channel for peer $peerId: name=$latestName, readyToSend=$latestReadyToSend")
                }
                return updatedChannel
            }
            return existingChannel
        }
        // Create new channel
        val newChannel = DirectMessageChannel(
            id = channelId,
            name = latestName,
            peerId = peerId,
            lastMessage = null,
            isPkiEncrypted = latestPki,
            readyToSend = latestReadyToSend,
            displayName = null
        )
        // Add to channels collection
        val currentChannels = _channels.value.toMutableList()
        currentChannels.add(newChannel)
        _channels.value = currentChannels
        Log.d(TAG, "Created new direct message channel for peer $peerId: name=$latestName, readyToSend=$latestReadyToSend")
        return newChannel
    }

    override suspend fun createChannel(name: String) {
        TODO("Not yet implemented")
    }

    override fun deleteChannel(channelId: String) {
        Log.d(TAG, "Deleting channel: $channelId")

        // Find the channel to delete
        val channel = _channels.value.find { it.id == channelId }
        if (channel == null) {
            Log.e(TAG, "Cannot delete channel: Channel $channelId not found")
            return
        }

        // Only allow deletion of secondary channels for MeshtasticChannel
        if (channel is MeshtasticChannel) {
            Log.e(TAG, "Cannot delete Meshtastic channels.")
        }

        // Remove channel from collection
        val currentChannels = _channels.value.toMutableList()
        currentChannels.removeIf { it.id == channelId }
        _channels.value = currentChannels

        // If this was the selected channel, select the primary channel instead
        if (channelId == selectedChannelId) {
            val primaryChannel = currentChannels.find { it is MeshtasticChannel && it.role == MeshtasticChannel.ChannelRole.PRIMARY }
            if (primaryChannel != null) {
                CoroutineScope(coroutineContext).launch {
                    selectChannel(primaryChannel.id)
                }
            }
        }

        // Clean up any messages for this channel
        val currentMessages = _channelMessages.value.toMutableMap()
        currentMessages.remove(channelId)
        _channelMessages.value = currentMessages
        channelLastMessages.remove(channelId)

        Log.d(TAG, "Channel $channelId deleted successfully")
    }

    internal fun stopPacketQueue() {
        Log.d(TAG, "Stopping packet queue")
        try {
            // Cancel the queue job first
            queueJob?.cancel()
            queueJob = null

            // Complete any pending responses with failure
            queueResponse.forEach { (_, deferred) ->
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
            queueResponse.clear()

            // Clear the queue
            queuedPackets.clear()

            // Cancel all timeout jobs
            timeoutJobManager.cancelAll()

            Log.d(TAG, "Packet queue stopped successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping packet queue", e)
        }
    }

    private fun startMessageTimeout(packet: MeshProtos.MeshPacket) {
        // Only start message timeout for text messages
        if (!packet.hasDecoded() || packet.decoded.portnum != PortNum.TEXT_MESSAGE_APP) {
            return
        }

        timeoutJobManager.startTimeout(packet, MESSAGE_TIMEOUT_MS) { timedOutPacket ->
            val unsignedPacketId = (timedOutPacket.id.toLong() and 0xFFFFFFFFL).toInt()
            var retryCount = messageRetryCount.getOrDefault(unsignedPacketId, 0)

            // Check if packet is still in flight (hasn't been acknowledged)
            if (inFlightMessages.containsKey(unsignedPacketId)) {
                // Check if the packet is still in the queue (BLE level retry)
                val isInQueue = queueResponse.containsKey(unsignedPacketId)

                if (retryCount < MAX_RETRY_COUNT && !isInQueue) {
                    // Only retry if not already being retried at BLE level
                    retryCount += 1

                    Log.w(TAG, "Packet $unsignedPacketId timed out, retry $retryCount of $MAX_RETRY_COUNT")

                    messageRetryCount[unsignedPacketId] = retryCount

                    // Start a new timeout for the retry attempt
                    startMessageTimeout(timedOutPacket)

                    // Queue the retry packet
                    queuePacket(timedOutPacket)
                } else if (retryCount >= MAX_RETRY_COUNT) {
                    // Max retries reached, mark as failed
                    inFlightMessages.remove(unsignedPacketId)
                    messageRetryCount.remove(unsignedPacketId)
                    updateMessageStatusForPacket(timedOutPacket, MessageStatus.FAILED)
                    Log.w(TAG, "Packet $unsignedPacketId failed after $MAX_RETRY_COUNT retries")
                } else {
                    Log.d(TAG, "Packet $unsignedPacketId is being retried at BLE level, skipping packet-level retry")
                }
            } else {
                // Packet was already handled by queue or routing response
                Log.d(TAG, "Packet $unsignedPacketId already handled, ignoring timeout")
            }
        }
    }

    internal fun recoverInFlightMessages() {
        Log.i(TAG, "Recovering in-flight messages after reconnection")
        inFlightMessages.values.forEach { packet ->
            queuePacket(packet)
            startMessageTimeout(packet)
        }
    }

    private fun getNodeInfoForPeer(peerId: String): MeshProtos.NodeInfo? {
        return nodeInfoMap[peerId]
    }

    /**
     * Get the local user's shortname and hwmodel for display purposes
     * @return Pair of shortname and hwmodel string, or null if not available
     */
    protected fun getLocalUserInfoInternal(): Pair<String, String>? {
        val localNodeId = connectedNodeId ?: return null
        val nodeInfo = nodeInfoMap[localNodeId] ?: return null
        val user = nodeInfo.user ?: return null
        
        val shortname = user.shortName.takeIf { it.isNotBlank() } ?: return null
        val hwmodel = user.hwModel.toString()
        
        Log.d(TAG, "Local user info available: shortname=$shortname, hwmodel=$hwmodel")
        return Pair(shortname, hwmodel)
    }

    override fun getLocalUserInfo(): Pair<String, String>? {
        return getLocalUserInfoInternal()
    }

    internal open fun cleanupState() {
        // Stop any ongoing operations first
        stopPacketQueue()

        // Clear all state
        peerLocations.clear()
        _annotations.value = emptyList()
        _peers.value = emptyList()
        connectedNodeId = null
        handshakeComplete.set(false)
        _configDownloadStep.value = ConfigDownloadStep.NotStarted
        _configStepCounters.value = emptyMap()  // Reset counters
        nodeInfoMap.clear()
        inFlightMessages.clear()
        messageRetryCount.clear()
        timeoutJobManager.cancelAll()
        lastSentMessage = null
        queuedPackets.clear()
        queueResponse.clear()
        channelLastMessages.clear()
        _channels.value = emptyList()
        _channelMessages.value = emptyMap()
    }
}