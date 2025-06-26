package com.tak.lite.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.MeshProtos
import com.tak.lite.model.ConfidenceCone
import com.tak.lite.model.LocationPrediction
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PredictionConfig
import com.tak.lite.model.PredictionModel
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import com.tak.lite.repository.MeshNetworkRepository
import com.tak.lite.repository.PeerLocationHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject

@HiltViewModel
class MeshNetworkViewModel @Inject constructor(
    private val meshNetworkRepository: MeshNetworkRepository,
    private val peerLocationHistoryRepository: PeerLocationHistoryRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<MeshNetworkUiState>(MeshNetworkUiState.Initial)
    val uiState: StateFlow<MeshNetworkUiState> = _uiState.asStateFlow()
    
    private val _peerLocations = MutableStateFlow<Map<String, LatLng>>(emptyMap())
    val peerLocations: StateFlow<Map<String, LatLng>> = _peerLocations.asStateFlow()
    
    val userLocation: StateFlow<org.maplibre.android.geometry.LatLng?> = meshNetworkRepository.userLocation as StateFlow<org.maplibre.android.geometry.LatLng?>
    val phoneLocation: StateFlow<org.maplibre.android.geometry.LatLng?> = meshNetworkRepository.phoneLocation as StateFlow<org.maplibre.android.geometry.LatLng?>
    val bestLocation: StateFlow<org.maplibre.android.geometry.LatLng?> = meshNetworkRepository.bestLocation as StateFlow<org.maplibre.android.geometry.LatLng?>
    val isDeviceLocationStale: StateFlow<Boolean> = meshNetworkRepository.isDeviceLocationStale as StateFlow<Boolean>
    
    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()
    
    private val _selfId = MutableStateFlow<String?>(null)
    val selfId: StateFlow<String?> = _selfId.asStateFlow()

    // State flows for predictions and confidence cones
    private val _predictions = MutableStateFlow<Map<String, LocationPrediction>>(emptyMap())
    val predictions: StateFlow<Map<String, LocationPrediction>> = _predictions.asStateFlow()

    private val _confidenceCones = MutableStateFlow<Map<String, ConfidenceCone>>(emptyMap())
    val confidenceCones: StateFlow<Map<String, ConfidenceCone>> = _confidenceCones.asStateFlow()
    
    init {
        viewModelScope.launch {
            combine(
                meshNetworkRepository.networkState,
                meshNetworkRepository.connectedPeers
            ) { state, peers ->
                when (state) {
                    is MeshNetworkState.Connected -> MeshNetworkUiState.Connected(peers)
                    is MeshNetworkState.Connecting -> MeshNetworkUiState.Connecting
                    is MeshNetworkState.Disconnected -> MeshNetworkUiState.Disconnected
                    is MeshNetworkState.Error -> MeshNetworkUiState.Error(state.message)
                }
            }.collect { newState ->
                _uiState.value = newState
            }
        }
        viewModelScope.launch {
            meshNetworkRepository.peerLocations.collect { locations ->
                val selfId = _selfId.value
                val filtered = if (selfId != null) locations.filterKeys { it != selfId } else locations
                Log.d("MeshNetworkViewModel", "Received peer locations: ${locations.size} total, ${filtered.size} after filtering, simulated=${filtered.keys.count { it.startsWith("sim_peer_") }}")
                _peerLocations.value = filtered
            }
        }
        viewModelScope.launch {
            meshNetworkRepository.packetSummaries.collect { _packetSummaries.value = it }
        }
        viewModelScope.launch {
            meshNetworkRepository.userLocation.collect { location ->
                Log.d("MeshNetworkViewModel", "User location updated: $location")
            }
        }
        viewModelScope.launch {
            meshNetworkRepository.selfId.collect { nodeId ->
                _selfId.value = nodeId
            }
        }

        viewModelScope.launch {
            peerLocationHistoryRepository.predictions.collect { _predictions.value = it }
        }
        viewModelScope.launch {
            peerLocationHistoryRepository.confidenceCones.collect { _confidenceCones.value = it }
        }
    }

    fun requestPeerLocation(peerId: String, onLocationReceived: (timeout: Boolean) -> Unit) {
        meshNetworkRepository.requestPeerLocation(peerId, onLocationReceived)
    }

    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            meshNetworkRepository.sendLocationUpdate(latitude, longitude)
        }
    }
    
    fun setLocalNickname(nickname: String) {
        meshNetworkRepository.setLocalNickname(nickname)
    }
    
    fun setPhoneLocation(latLng: LatLng) {
        meshNetworkRepository.setPhoneLocation(latLng)
    }
    
    override fun onCleared() {
        super.onCleared()
        meshNetworkRepository.cleanup()
    }
    
    suspend fun getNodeInfo(peerId: String): MeshProtos.NodeInfo? {
        return meshNetworkRepository.getNodeInfo(peerId)
    }
}

sealed class MeshNetworkUiState {
    data object Initial : MeshNetworkUiState()
    data object Disconnected : MeshNetworkUiState()
    data object Connecting : MeshNetworkUiState()
    data class Connected(val peers: List<MeshPeer> = emptyList()) : MeshNetworkUiState()
    data class Error(val message: String) : MeshNetworkUiState()
} 