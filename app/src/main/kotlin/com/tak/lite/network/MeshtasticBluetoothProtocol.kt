package com.tak.lite.network

import android.bluetooth.BluetoothGatt
import android.util.Log
import kotlinx.coroutines.*
import java.util.*
import kotlin.coroutines.CoroutineContext
import com.geeksville.mesh.MeshProtos
import com.tak.lite.model.MapAnnotation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.ConcurrentHashMap
import com.tak.lite.util.MeshAnnotationInterop
import com.google.protobuf.ByteString
import com.tak.lite.util.DeviceController
import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

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
    private val peerLocations = ConcurrentHashMap<String, LatLng>()
    private val _annotations = MutableStateFlow<List<MapAnnotation>>(emptyList())
    val annotations: StateFlow<List<MapAnnotation>> = _annotations.asStateFlow()
    private val _connectionMetrics = MutableStateFlow(ConnectionMetrics())
    val connectionMetrics: StateFlow<ConnectionMetrics> = _connectionMetrics.asStateFlow()
    private val peersMap = ConcurrentHashMap<String, MeshPeer>()
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    init {
        deviceManager.setPacketListener { data ->
            handleIncomingPacket(data)
        }
    }

    fun sendPacket(data: ByteArray) {
        Log.d(TAG, "sendPacket: Sending ${data.size} bytes: ${data.joinToString(", ", limit = 16)}")
        CoroutineScope(coroutineContext).launch {
            try {
                val gatt = deviceManager.connectedGatt ?: return@launch
                val service = gatt.getService(MESHTASTIC_SERVICE_UUID) ?: return@launch
                val characteristic = service.getCharacteristic(TORADIO_CHARACTERISTIC_UUID) ?: return@launch
                characteristic.value = data
                gatt.writeCharacteristic(characteristic)
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet: ${e.message}")
            }
        }
    }

    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
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
            .setFrom(0)
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .build()
        sendPacket(packet.toByteArray())
    }

    fun sendAnnotation(annotation: MapAnnotation) {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val nickname = prefs.getString("nickname", null)
        val battery = DeviceController.batteryLevel.value
        val data = MeshAnnotationInterop.mapAnnotationToMeshData(
            annotation = annotation,
            nickname = nickname,
            batteryLevel = battery
        )
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0)
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .build()
        Log.d(TAG, "Sending annotation: $annotation as packet bytes: ${packet.toByteArray().joinToString(", ", limit = 16)}")
        sendPacket(packet.toByteArray())
    }

    // Call this when you receive a packet from the device
    fun handleIncomingPacket(data: ByteArray) {
        Log.d(TAG, "Received packet from device: ${data.size} bytes: ${data.joinToString(", ", limit = 16)}")
        try {
            val meshPacket = MeshProtos.MeshPacket.parseFrom(data)
            Log.d(TAG, "Parsed MeshPacket: $meshPacket")
            if (meshPacket.hasDecoded()) {
                val decoded = meshPacket.decoded
                val peerId = meshPacket.from.toString()
                // Update peer list
                peersMap[peerId] = MeshPeer(
                    id = peerId,
                    ipAddress = "N/A",
                    lastSeen = System.currentTimeMillis(),
                    nickname = null // Meshtastic packets may not have nickname
                )
                _peers.value = peersMap.values.toList()
                if (decoded.portnum == com.geeksville.mesh.Portnums.PortNum.POSITION_APP) {
                    try {
                        val position = MeshProtos.Position.parseFrom(decoded.payload)
                        val lat = position.latitudeI / 1e7
                        val lng = position.longitudeI / 1e7
                        Log.d(TAG, "Parsed position from peer $peerId: lat=$lat, lng=$lng")
                        peerLocations[peerId] = LatLng(lat, lng)
                        // Do not create an annotation, just update peer locations
                        peerLocationCallback?.invoke(peerLocations.toMap())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse position: ${e.message}")
                    }
                } else {
                    val annotation = MeshAnnotationInterop.meshDataToMapAnnotation(decoded)
                    if (annotation != null) {
                        Log.d(TAG, "Parsed annotation from peer $peerId: $annotation")
                        annotationCallback?.invoke(annotation)
                        _annotations.value = _annotations.value + annotation
                    } else {
                        Log.d(TAG, "Received non-annotation, non-location message (portnum: ${decoded.portnum})")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming packet: ${e.message}")
        }
    }

    data class ConnectionMetrics(
        val packetLoss: Float = 0f,
        val latency: Long = 0L,
        val jitter: Long = 0L,
        val lastUpdate: Long = System.currentTimeMillis(),
        val networkQuality: Float = 1.0f
    )
} 