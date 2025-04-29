package com.tak.lite.network

import android.util.Log
import com.tak.lite.model.MapAnnotation
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

class MeshNetworkProtocol(
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    private val TAG = "MeshNetworkProtocol"
    private val DISCOVERY_PORT = 5000
    private val ANNOTATION_PORT = 5001
    private val DISCOVERY_INTERVAL_MS = 5000L
    private val PEER_TIMEOUT_MS = 15000L
    
    private var discoveryJob: Job? = null
    private var broadcastSocket: DatagramSocket? = null
    private var listenerJob: Job? = null
    private var annotationListenerJob: Job? = null
    
    private val peers = mutableMapOf<String, MeshPeer>()
    private var peerUpdateCallback: ((List<MeshPeer>) -> Unit)? = null
    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    
    private val json = Json { ignoreUnknownKeys = true }
    
    fun startDiscovery(callback: (List<MeshPeer>) -> Unit) {
        peerUpdateCallback = callback
        discoveryJob = CoroutineScope(coroutineContext).launch {
            while (isActive) {
                try {
                    sendDiscoveryPacket()
                    delay(DISCOVERY_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error during discovery: ${e.message}")
                }
            }
        }
        
        startDiscoveryListener()
        startAnnotationListener()
    }
    
    private fun startDiscoveryListener() {
        listenerJob = CoroutineScope(coroutineContext).launch {
            try {
                broadcastSocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    try {
                        broadcastSocket?.receive(packet)
                        handleDiscoveryPacket(packet)
                    } catch (e: SocketTimeoutException) {
                        // Continue listening
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving discovery packet: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up discovery listener: ${e.message}")
            }
        }
    }
    
    private fun startAnnotationListener() {
        annotationListenerJob = CoroutineScope(coroutineContext).launch {
            try {
                val socket = DatagramSocket(ANNOTATION_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                
                val buffer = ByteArray(8192) // Increased buffer size for larger annotations
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    try {
                        socket.receive(packet)
                        handleAnnotationPacket(packet)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error receiving annotation packet: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error setting up annotation listener: ${e.message}")
            }
        }
    }
    
    private suspend fun sendDiscoveryPacket() {
        try {
            val socket = DatagramSocket().apply {
                broadcast = true
                reuseAddress = true
            }
            
            val message = "TAK_LITE_DISCOVERY".toByteArray()
            val packet = DatagramPacket(
                message,
                message.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            
            socket.send(packet)
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending discovery packet: ${e.message}")
        }
    }
    
    private fun handleDiscoveryPacket(packet: DatagramPacket) {
        val message = String(packet.data, 0, packet.length)
        if (message == "TAK_LITE_DISCOVERY") {
            val peerId = "${packet.address.hostAddress}:${packet.port}"
            val peer = MeshPeer(
                id = peerId,
                ipAddress = packet.address.hostAddress,
                lastSeen = System.currentTimeMillis()
            )
            
            peers[peerId] = peer
            cleanupOldPeers()
            peerUpdateCallback?.invoke(peers.values.toList())
        }
    }
    
    private fun handleAnnotationPacket(packet: DatagramPacket) {
        try {
            val jsonString = String(packet.data, 0, packet.length)
            val annotation = json.decodeFromString<MapAnnotation>(jsonString)
            annotationCallback?.invoke(annotation)
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing annotation packet: ${e.message}")
        }
    }
    
    private fun cleanupOldPeers() {
        val now = System.currentTimeMillis()
        peers.entries.removeIf { (_, peer) ->
            now - peer.lastSeen > PEER_TIMEOUT_MS
        }
    }
    
    fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }
    
    fun stopDiscovery() {
        discoveryJob?.cancel()
        listenerJob?.cancel()
        annotationListenerJob?.cancel()
        broadcastSocket?.close()
        peers.clear()
        peerUpdateCallback = null
        annotationCallback = null
    }
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        val message = ByteBuffer.allocate(16).apply {
            order(ByteOrder.LITTLE_ENDIAN)
            putDouble(latitude)
            putDouble(longitude)
        }.array()
        
        sendToAllPeers(message, DISCOVERY_PORT + 1)
    }
    
    fun sendAnnotation(annotation: MapAnnotation) {
        try {
            val jsonString = json.encodeToString(MapAnnotation.serializer(), annotation)
            sendToAllPeers(jsonString.toByteArray(), ANNOTATION_PORT)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending annotation: ${e.message}")
        }
    }
    
    fun sendAudioData(audioData: ByteArray) {
        sendToAllPeers(audioData, DISCOVERY_PORT + 2)
    }
    
    private fun sendToAllPeers(data: ByteArray, port: Int) {
        peers.values.forEach { peer ->
            try {
                val socket = DatagramSocket()
                val packet = DatagramPacket(
                    data,
                    data.size,
                    InetAddress.getByName(peer.ipAddress),
                    port
                )
                socket.send(packet)
                socket.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending data to peer ${peer.id}: ${e.message}")
            }
        }
    }
} 