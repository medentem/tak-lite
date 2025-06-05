package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.geeksville.mesh.MeshProtos
import com.tak.lite.model.PacketSummary
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import com.tak.lite.repository.MeshNetworkRepository
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
    private val meshNetworkRepository: MeshNetworkRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<MeshNetworkUiState>(MeshNetworkUiState.Initial)
    val uiState: StateFlow<MeshNetworkUiState> = _uiState.asStateFlow()
    
    private val _peerLocations = MutableStateFlow<Map<String, LatLng>>(emptyMap())
    val peerLocations: StateFlow<Map<String, LatLng>> = _peerLocations.asStateFlow()
    
    val userLocation: StateFlow<org.maplibre.android.geometry.LatLng?> = meshNetworkRepository.userLocation as StateFlow<org.maplibre.android.geometry.LatLng?>
    val isDeviceLocationStale: StateFlow<Boolean> = meshNetworkRepository.isDeviceLocationStale as StateFlow<Boolean>
    
    private val _phoneLocation = MutableStateFlow<LatLng?>(null)
    val phoneLocation: StateFlow<LatLng?> = _phoneLocation.asStateFlow()
    
    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()
    
    init {
        viewModelScope.launch {
            combine(
                meshNetworkRepository.networkState,
                meshNetworkRepository.connectedPeers
            ) { state, peers ->
                when (state) {
                    is MeshNetworkState.Connected -> MeshNetworkUiState.Connected(peers)
                    is MeshNetworkState.Disconnected -> MeshNetworkUiState.Disconnected
                    is MeshNetworkState.Error -> MeshNetworkUiState.Error(state.message)
                }
            }.collect { newState ->
                _uiState.value = newState
            }
        }
        viewModelScope.launch {
            meshNetworkRepository.peerLocations.collect { locations ->
                val selfId = meshNetworkRepository.selfId
                val filtered = if (selfId != null) locations.filterKeys { it != selfId } else locations
                _peerLocations.value = filtered
            }
        }
        viewModelScope.launch {
            meshNetworkRepository.packetSummaries.collect { _packetSummaries.value = it }
        }
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
        _phoneLocation.value = latLng
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
    data class Connected(val peers: List<MeshPeer> = emptyList()) : MeshNetworkUiState()
    data class Error(val message: String) : MeshNetworkUiState()
} 