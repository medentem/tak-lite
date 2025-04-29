package com.tak.lite.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshNetworkService @Inject constructor(
    private val context: Context,
    private val meshProtocol: MeshNetworkProtocol
) {
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val networkScope = CoroutineScope(Dispatchers.IO)
    
    private val _networkState = MutableStateFlow<MeshNetworkState>(MeshNetworkState.Disconnected)
    val networkState: StateFlow<MeshNetworkState> = _networkState
    
    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val peers: StateFlow<List<MeshPeer>> = _peers
    
    private var meshNetworkCallback: ConnectivityManager.NetworkCallback? = null
    
    init {
        setupNetworkMonitoring()
    }
    
    private fun setupNetworkMonitoring() {
        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .build()
            
        meshNetworkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                networkScope.launch {
                    checkMeshNetworkConnection(network)
                }
            }
            
            override fun onLost(network: Network) {
                networkScope.launch {
                    _networkState.value = MeshNetworkState.Disconnected
                    _peers.value = emptyList()
                    meshProtocol.stopDiscovery()
                }
            }
        }
        
        connectivityManager.registerNetworkCallback(networkRequest, meshNetworkCallback!!)
    }
    
    private suspend fun checkMeshNetworkConnection(network: Network) {
        try {
            val networkInterface = NetworkInterface.getByInetAddress(
                InetAddress.getByName("10.223.0.1")
            )
            
            if (networkInterface != null) {
                _networkState.value = MeshNetworkState.Connected
                startPeerDiscovery()
            }
        } catch (e: Exception) {
            _networkState.value = MeshNetworkState.Disconnected
        }
    }
    
    private fun startPeerDiscovery() {
        meshProtocol.startDiscovery { discoveredPeers ->
            _peers.value = discoveredPeers
        }
    }
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        meshProtocol.sendLocationUpdate(latitude, longitude)
    }
    
    fun sendAudioData(audioData: ByteArray) {
        meshProtocol.sendAudioData(audioData)
    }
    
    fun cleanup() {
        meshNetworkCallback?.let {
            connectivityManager.unregisterNetworkCallback(it)
        }
        meshProtocol.stopDiscovery()
    }
}

sealed class MeshNetworkState {
    object Connected : MeshNetworkState()
    object Disconnected : MeshNetworkState()
    data class Error(val message: String) : MeshNetworkState()
}

data class MeshPeer(
    val id: String,
    val ipAddress: String,
    val lastSeen: Long
) 