package com.tak.lite.network

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
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

class MeshtasticBluetoothProtocol(
    private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    private val TAG = "MeshtasticBluetoothProtocol"
    private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var connectionJob: Job? = null
    private var readJob: Job? = null
    private var isConnected = false
    private var selectedDevice: BluetoothDevice? = null
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

    fun showScanDialog(onDeviceSelected: (BluetoothDevice) -> Unit) {
        val adapter = bluetoothAdapter ?: return
        val discoveredDevices = mutableListOf<BluetoothDevice>()
        val deviceNames = mutableListOf<String>()
        val discoveryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                val action = intent.action
                if (BluetoothDevice.ACTION_FOUND == action) {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (!discoveredDevices.contains(it)) {
                            discoveredDevices.add(it)
                            val name = it.name ?: "Unknown"
                            val highlight = if (name.contains("Meshtastic", ignoreCase = true)) " (Meshtastic)" else ""
                            deviceNames.add("$name [$highlight] (${it.address})")
                        }
                    }
                }
            }
        }
        val filter = android.content.IntentFilter(BluetoothDevice.ACTION_FOUND)
        context.registerReceiver(discoveryReceiver, filter)
        adapter.startDiscovery()

        // Show loading dialog immediately
        val progressDialog = android.app.AlertDialog.Builder(context)
            .setTitle("Scanning for devices...")
            .setView(android.widget.ProgressBar(context))
            .setCancelable(true)
            .create()
        progressDialog.show()

        // After scan, update dialog with device list
        CoroutineScope(Dispatchers.Main).launch {
            delay(4000)
            adapter.cancelDiscovery()
            context.unregisterReceiver(discoveryReceiver)
            progressDialog.dismiss()
            if (deviceNames.isEmpty()) {
                android.app.AlertDialog.Builder(context)
                    .setTitle("No devices found")
                    .setMessage("No Bluetooth devices were found. Make sure your device is discoverable and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Select Meshtastic Device")
                    .setItems(deviceNames.toTypedArray()) { _, which ->
                        val device = discoveredDevices[which]
                        onDeviceSelected(device)
                    }
                    .setCancelable(true)
                    .show()
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        connectionJob?.cancel()
        connectionJob = CoroutineScope(coroutineContext).launch {
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                bluetoothAdapter?.cancelDiscovery()
                bluetoothSocket?.connect()
                isConnected = true
                selectedDevice = device
                onConnected(true)
                startReadLoop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to connect: ${e.message}")
                isConnected = false
                onConnected(false)
            }
        }
    }

    private fun startReadLoop() {
        readJob?.cancel()
        readJob = CoroutineScope(coroutineContext).launch {
            val socket = bluetoothSocket ?: return@launch
            val input: InputStream = socket.inputStream
            try {
                val buffer = ByteArray(8192)
                while (isConnected && socket.isConnected) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead > 0) {
                        val packet = buffer.copyOf(bytesRead)
                        handleIncomingPacket(packet)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reading from Bluetooth: ${e.message}")
                isConnected = false
            }
        }
    }

    fun sendPacket(data: ByteArray) {
        CoroutineScope(coroutineContext).launch {
            try {
                val socket = bluetoothSocket ?: return@launch
                val output: OutputStream = socket.outputStream
                output.write(data)
                output.flush()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet: ${e.message}")
            }
        }
    }

    fun disconnect() {
        isConnected = false
        connectionJob?.cancel()
        readJob?.cancel()
        try {
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        bluetoothSocket = null
        selectedDevice = null
    }

    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    fun setPeerLocationCallback(callback: (Map<String, LatLng>) -> Unit) {
        peerLocationCallback = callback
    }

    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        val position = MeshProtos.Position.newBuilder()
            .setLatitudeI((latitude * 1e7).toInt())
            .setLongitudeI((longitude * 1e7).toInt())
            .setTime((System.currentTimeMillis() / 1000).toInt())
            .build()
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.POSITION_APP)
            .setPayload(ByteString.copyFrom(position.toByteArray()))
            .build()
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0) // Let firmware handle node id
            .setTo(0xffffffffL.toInt()) // broadcast
            .setDecoded(data)
            .build()
        sendPacket(packet.toByteArray())
    }

    fun sendAnnotation(annotation: MapAnnotation) {
        val data = MeshAnnotationInterop.mapAnnotationToMeshData(annotation)
        val packet = MeshProtos.MeshPacket.newBuilder()
            .setFrom(0)
            .setTo(0xffffffffL.toInt())
            .setDecoded(data)
            .build()
        sendPacket(packet.toByteArray())
    }

    private fun handleIncomingPacket(data: ByteArray) {
        try {
            val meshPacket = MeshProtos.MeshPacket.parseFrom(data)
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
                        peerLocations[peerId] = LatLng(lat, lng)
                        peerLocationCallback?.invoke(peerLocations.toMap())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse position: ${e.message}")
                    }
                } else {
                    val annotation = MeshAnnotationInterop.meshDataToMapAnnotation(decoded)
                    if (annotation != null) {
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