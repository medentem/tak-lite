package com.tak.lite.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tak.lite.network.MeshNetworkState
import com.tak.lite.network.MeshPeer
import com.tak.lite.repository.MeshNetworkRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import org.maplibre.android.geometry.LatLng

@HiltViewModel
class MeshNetworkViewModel @Inject constructor(
    private val meshNetworkRepository: MeshNetworkRepository
) : ViewModel() {
    
    private val _uiState = MutableStateFlow<MeshNetworkUiState>(MeshNetworkUiState.Initial)
    val uiState: StateFlow<MeshNetworkUiState> = _uiState.asStateFlow()
    
    private val _peerLocations = MutableStateFlow<Map<String, LatLng>>(emptyMap())
    val peerLocations: StateFlow<Map<String, LatLng>> = _peerLocations.asStateFlow()
    
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
                _peerLocations.value = locations
            }
        }
    }
    
    fun sendLocationUpdate(latitude: Double, longitude: Double) {
        viewModelScope.launch {
            meshNetworkRepository.sendLocationUpdate(latitude, longitude)
        }
    }
    
    fun sendAudioData(audioData: ByteArray) {
        viewModelScope.launch {
            meshNetworkRepository.sendAudioData(audioData)
        }
    }
    
    fun setLocalNickname(nickname: String) {
        meshNetworkRepository.setLocalNickname(nickname)
    }
    
    override fun onCleared() {
        super.onCleared()
        meshNetworkRepository.cleanup()
    }
}

sealed class MeshNetworkUiState {
    object Initial : MeshNetworkUiState()
    object Disconnected : MeshNetworkUiState()
    data class Connected(val peers: List<MeshPeer> = emptyList()) : MeshNetworkUiState()
    data class Error(val message: String) : MeshNetworkUiState()
} 