package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.MeshProtos.ToRadio
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.DeviceController
import com.tak.lite.util.MeshAnnotationInterop
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class MeshtasticBluetoothProtocol @Inject constructor(
    private val deviceManager: BluetoothDeviceManager,
    @ApplicationContext private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    private val TAG = "MeshtasticBluetoothProtocol"
    // Official Meshtastic Service UUIDs and Characteristics
    private val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val FROMRADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val TORADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROMNUM_CHARACTERISTIC_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    private var peerLocationCallback: ((Map<String, LatLng>) -> Unit)? = null
    private var userLocationCallback: ((LatLng) -> Unit)? = null
    private val peerLocations = ConcurrentHashMap<String, LatLng>()
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()
    private var connectedNodeId: String? = null
    private val handshakeComplete = AtomicBoolean(false)
    private val configNonce = AtomicInteger((System.currentTimeMillis() % Int.MAX_VALUE).toInt())

    init {
        deviceManager.setPacketListener { data ->
            handleIncomingPacket(data)
        }
        deviceManager.setLostConnectionCallback {
            Log.w(TAG, "Lost connection to device. State will be cleared.")
            peerLocations.clear()
            _annotations.value = emptyList()
            _peers.value = emptyList()
            connectedNodeId = null
            handshakeComplete.set(false)
        }
        // Listen for initial drain complete to trigger handshake
        deviceManager.setInitialDrainCompleteCallback {
            Log.i(TAG, "Initial FROMRADIO drain complete, starting config handshake.")
            startConfigHandshake()
        }
    }

    fun sendPacket(data: ByteArray) {
        val MAXPACKET = 256
        if (data.size > MAXPACKET) {
            Log.e(TAG, "sendPacket: Data size \\${data.size} exceeds MAXPACKET (\\$MAXPACKET), not sending.")
            return
        }
        // Wrap the MeshPacket bytes in a ToRadio message
        val toRadio = ToRadio.newBuilder()
            .setPacket(MeshProtos.MeshPacket.parseFrom(data))
            .build()
        val toRadioBytes = toRadio.toByteArray()
        Log.d(TAG, "sendPacket: Sending ToRadio \\${toRadioBytes.size} bytes: \\${toRadioBytes.joinToString(", ", limit = 16)}")
        CoroutineScope(coroutineContext).launch {
            try {
                val gatt = deviceManager.connectedGatt
                val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
                val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
                if (gatt != null && service != null && toRadioChar != null) {
                    deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                        result.onSuccess { success ->
                            Log.d(TAG, "sendPacket: reliableWrite returned \\${success}")
                        }.onFailure { error ->
                            Log.e(TAG, "sendPacket: reliableWrite error: \\${error.message}")
                        }
                    }
                } else {
                    Log.e(TAG, "sendPacket: GATT/service/ToRadio characteristic missing, cannot send packet.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet: \\${e.message}")
            }
        }
    }

    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
    }

    fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        userLocationCallback = callback
    }

    fun sendLocationUpdate(latitude: Double, longitude: Double) {
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
            .build()
        sendPacket(packet.toByteArray())
    }

    fun sendAnnotation(annotation: MapAnnotation) {
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
            .build()
        Log.d(TAG, "Sending annotation: $annotation as packet bytes: "+
            packet.toByteArray().joinToString(", ", limit = 16))
        sendPacket(packet.toByteArray())
    }

    private fun startConfigHandshake() {
        handshakeComplete.set(false)
        val nonce = configNonce.incrementAndGet()
        Log.i(TAG, "Starting Meshtastic config handshake with nonce=$nonce")
        val toRadio = com.geeksville.mesh.MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(nonce)
            .build()
        val toRadioBytes = toRadio.toByteArray()
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
            if (gatt != null && service != null && toRadioChar != null) {
                deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                    result.onSuccess { success ->
                        Log.i(TAG, "Sent want_config_id handshake packet: $success")
                        // Start handshake drain loop
                        drainFromRadioUntilHandshakeComplete()
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send want_config_id: ${error.message}")
                    }
                }
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send handshake.")
            }
        }
    }

    private fun drainFromRadioUntilHandshakeComplete() {
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
            if (gatt != null && fromRadioChar != null) {
                while (!handshakeComplete.get()) {
                    deviceManager.aggressiveDrainFromRadio(gatt, fromRadioChar)
                    // Wait a short time before next drain attempt to avoid tight loop
                    kotlinx.coroutines.delay(200)
                }
                Log.i(TAG, "Handshake complete.")
            } else {
                Log.e(TAG, "GATT/service/FROMRADIO characteristic missing, cannot drain during handshake.")
            }
        }
    }

    // Call this when you receive a packet from the device
    fun handleIncomingPacket(data: ByteArray) {
        Log.d(TAG, "handleIncomingPacket called with data: ${data.size} bytes: ${data.joinToString(", ", limit = 16)}")
        try {
            val fromRadio = com.geeksville.mesh.MeshProtos.FromRadio.parseFrom(data)
            Log.d(TAG, "Parsed FromRadio: $fromRadio")
            when (fromRadio.payloadVariantCase) {
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> {
                    Log.i(TAG, "Received CONFIG during handshake.")
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> {
                    Log.i(TAG, "Received MODULECONFIG during handshake.")
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> {
                    Log.i(TAG, "Received CHANNEL during handshake.")
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                    Log.i(TAG, "Received NODE_INFO during handshake.")
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                    val myInfo = fromRadio.myInfo
                    connectedNodeId = myInfo.myNodeNum.toString()
                    Log.d(TAG, "Received MyNodeInfo, nodeId: $connectedNodeId")
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                    val completeId = fromRadio.configCompleteId
                    Log.i(TAG, "Received CONFIG_COMPLETE_ID: $completeId (expecting ${configNonce.get()})")
                    if (completeId == configNonce.get()) {
                        handshakeComplete.set(true)
                        Log.i(TAG, "Meshtastic config handshake complete!")
                        // Subscribe to FROMNUM notifications only after handshake is complete
                        deviceManager.subscribeToFromNumNotifications()
                    } else {
                        Log.w(TAG, "Received stale config_complete_id: $completeId")
                    }
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                    if (!handshakeComplete.get()) {
                        Log.w(TAG, "Ignoring mesh packet before handshake complete.")
                        return
                    }
                    val meshPacket = fromRadio.packet
                    Log.d(TAG, "Parsed MeshPacket: $meshPacket")
                    if (meshPacket.hasDecoded()) {
                        val decoded = meshPacket.decoded
                        val peerId = meshPacket.from.toString()
                        Log.d(TAG, "Decoded payload from peer $peerId: $decoded")
                        peersMap[peerId] = MeshPeer(
                            id = peerId,
                            ipAddress = "N/A",
                            lastSeen = System.currentTimeMillis(),
                            nickname = null
                        )
                        _peers.value = peersMap.values.toList()
                        Log.d(TAG, "Updated peer marker for $peerId")
                        if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.POSITION_APP) {
                            try {
                                val position = com.geeksville.mesh.MeshProtos.Position.parseFrom(decoded.payload)
                                val lat = position.latitudeI / 1e7
                                val lng = position.longitudeI / 1e7
                                Log.d(TAG, "Parsed position from peer $peerId: lat=$lat, lng=$lng")
                                peerLocations[peerId] = org.maplibre.android.geometry.LatLng(lat, lng)
                                peerLocationCallback?.invoke(peerLocations.toMap())
                                if (isOwnNode(peerId)) {
                                    userLocationCallback?.invoke(org.maplibre.android.geometry.LatLng(lat, lng))
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to parse position: ${e.message}")
                            }
                        } else if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN) {
                            val annotation = com.tak.lite.util.MeshAnnotationInterop.meshDataToMapAnnotation(decoded)
                            if (annotation != null) {
                                Log.d(TAG, "Parsed annotation from peer $peerId: $annotation")
                                annotationCallback?.invoke(annotation)
                                _annotations.value = _annotations.value + annotation
                            } else {
                                Log.d(TAG, "Received TAK_LITE_APP message from $peerId but failed to parse annotation")
                            }
                        } else {
                            Log.d(TAG, "Ignored packet from $peerId with portnum: ${decoded.portnum}")
                        }
                    } else {
                        Log.d(TAG, "MeshPacket has no decoded payload")
                    }
                }
                else -> {
                    Log.d(TAG, "Ignored packet with payloadVariantCase: ${fromRadio.payloadVariantCase}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming packet: ${e.message}", e)
        }
    }

    // Helper to determine if a peerId is our own node
    private fun isOwnNode(peerId: String): Boolean {
        return connectedNodeId != null && peerId == connectedNodeId
    }

    data class ConnectionMetrics(
        val packetLoss: Float = 0f,
        val latency: Long = 0L,
        val jitter: Long = 0L,
        val lastUpdate: Long = System.currentTimeMillis(),
        val networkQuality: Float = 1.0f
    )
} 