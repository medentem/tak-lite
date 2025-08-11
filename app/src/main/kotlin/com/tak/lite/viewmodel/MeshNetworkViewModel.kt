package com.tak.lite.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.data.model.ConfidenceCone
import com.tak.lite.data.model.LocationPrediction
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
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
    
    // Private MutableStateFlow properties for internal state
    private val _networkState = MutableStateFlow<MeshNetworkUiState>(MeshNetworkUiState.Disconnected)
    val networkState: StateFlow<MeshNetworkUiState> = _networkState.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<MeshPeer>>(emptyList())
    val connectedPeers: StateFlow<List<MeshPeer>> = _connectedPeers.asStateFlow()
    
    private val _peerLocations = MutableStateFlow<Map<String, PeerLocationEntry>>(emptyMap())
    val peerLocations: StateFlow<Map<String, PeerLocationEntry>> = _peerLocations.asStateFlow()
    
    private val _userLocation = MutableStateFlow<LatLng?>(null)
    val userLocation: StateFlow<LatLng?> = _userLocation.asStateFlow()
    
    private val _phoneLocation = MutableStateFlow<LatLng?>(null)
    val phoneLocation: StateFlow<LatLng?> = _phoneLocation.asStateFlow()
    
    private val _bestLocation = MutableStateFlow<LatLng?>(null)
    val bestLocation: StateFlow<LatLng?> = _bestLocation.asStateFlow()
    
    private val _isDeviceLocationStale = MutableStateFlow<Boolean>(false)
    val isDeviceLocationStale: StateFlow<Boolean> = _isDeviceLocationStale.asStateFlow()

    private val _userStatus = MutableStateFlow(com.tak.lite.model.UserStatus.GREEN)
    val userStatus: StateFlow<com.tak.lite.model.UserStatus> = _userStatus.asStateFlow()
    
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
                _networkState.value = newState
            }
        }
        
        viewModelScope.launch {
            meshNetworkRepository.connectedPeers.collect { peers ->
                _connectedPeers.value = peers
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
            meshNetworkRepository.userLocation.collect { location ->
                _userLocation.value = location
                Log.d("MeshNetworkViewModel", "User location updated: $location")
            }
        }
        
        viewModelScope.launch {
            meshNetworkRepository.phoneLocation.collect { location ->
                _phoneLocation.value = location
            }
        }
        
        viewModelScope.launch {
            meshNetworkRepository.bestLocation.collect { location ->
                _bestLocation.value = location
            }
        }
        
        viewModelScope.launch {
            meshNetworkRepository.isDeviceLocationStale.collect { stale ->
                _isDeviceLocationStale.value = stale
            }
        }
        
        viewModelScope.launch {
            meshNetworkRepository.packetSummaries.collect { summaries ->
                _packetSummaries.value = summaries
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

        viewModelScope.launch {
            meshNetworkRepository.userStatus.collect { status ->
                _userStatus.value = status
            }
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

    fun getPeerName(peerId: String): String? {
        return meshNetworkRepository.getPeerName(peerId)
    }

    fun getPeerLastHeard(peerId: String): Long? {
        return meshNetworkRepository.getPeerLastHeard(peerId)
    }

    fun setUserStatus(status: com.tak.lite.model.UserStatus) {
        meshNetworkRepository.setUserStatus(status)
    }
    
    /**
     * Update prediction viewport bounds for performance optimization
     * This triggers viewport-based filtering in the prediction repository
     */
    fun updatePredictionViewport(viewportBounds: android.graphics.RectF?) {
        peerLocationHistoryRepository.updateViewportBounds(viewportBounds)
    }
    
    override fun onCleared() {
        super.onCleared()
        meshNetworkRepository.cleanup()
    }
}

sealed class MeshNetworkUiState {
    data object Initial : MeshNetworkUiState()
    data object Disconnected : MeshNetworkUiState()
    data object Connecting : MeshNetworkUiState()
    data class Connected(val peers: List<MeshPeer> = emptyList()) : MeshNetworkUiState()
    data class Error(val message: String) : MeshNetworkUiState()
} 