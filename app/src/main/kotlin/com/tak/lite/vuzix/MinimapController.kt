package com.tak.lite.vuzix

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Minimap Controller for Vuzix Z100 Smart Glasses
 * Manages minimap state and user interactions
 */
@HiltViewModel
class MinimapController @Inject constructor(
    private val minimapService: MinimapService
) : ViewModel() {

    companion object {
        private const val TAG = "MinimapController"
    }

    // Minimap state
    private val _isMinimapEnabled = MutableStateFlow(false)
    val isMinimapEnabled: StateFlow<Boolean> = _isMinimapEnabled.asStateFlow()

    private val _isVuzixConnected = MutableStateFlow(false)
    val isVuzixConnected: StateFlow<Boolean> = _isVuzixConnected.asStateFlow()


    init {
        Log.d(TAG, "=== MINIMAP CONTROLLER INITIALIZATION ===")
        Log.d(TAG, "MinimapController created")
        startMinimapService()
    }

    /**
     * Start minimap service
     */
    private fun startMinimapService() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting minimap service...")
                minimapService.startMinimapService()
                _isMinimapEnabled.value = true
                Log.d(TAG, "✅ Minimap service started successfully")
                
                // Monitor Vuzix connection state
                monitorVuzixConnection()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start minimap service", e)
                _isMinimapEnabled.value = false
            }
        }
    }

    /**
     * Monitor Vuzix connection state
     */
    private fun monitorVuzixConnection() {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Starting Vuzix connection monitoring...")
                
                // Get the VuzixManager from MinimapService to monitor connection
                val vuzixManager = minimapService.getVuzixManager()
                vuzixManager.isConnected.collect { isConnected ->
                    Log.d(TAG, "=== VUZIX CONNECTION STATE UPDATE ===")
                    Log.d(TAG, "Connection state changed: $isConnected")
                    Log.d(TAG, "Previous state: ${_isVuzixConnected.value}")
                    
                    _isVuzixConnected.value = isConnected
                    
                    if (isConnected) {
                        Log.i(TAG, "✅ Vuzix glasses are CONNECTED!")
                        Log.i(TAG, "Minimap can now be displayed on glasses")
                    } else {
                        Log.w(TAG, "❌ Vuzix glasses are NOT CONNECTED")
                        Log.w(TAG, "Minimap cannot be displayed")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error monitoring Vuzix connection", e)
            }
        }
    }

    /**
     * Show minimap
     */
    fun showMinimap() {
        viewModelScope.launch {
            try {
                minimapService.showMinimap()
                Log.d(TAG, "Minimap shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show minimap", e)
            }
        }
    }

    /**
     * Hide minimap
     */
    fun hideMinimap() {
        viewModelScope.launch {
            try {
                minimapService.hideMinimap()
                Log.d(TAG, "Minimap hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide minimap", e)
            }
        }
    }

    /**
     * Toggle minimap visibility
     */
    fun toggleMinimap() {
        viewModelScope.launch {
            try {
                minimapService.toggleMinimap()
                Log.d(TAG, "Minimap toggled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to toggle minimap", e)
            }
        }
    }

    /**
     * Handle voice command
     */
    fun handleVoiceCommand(command: String) {
        viewModelScope.launch {
            try {
                minimapService.handleVoiceCommand(command)
                Log.d(TAG, "Voice command handled: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle voice command", e)
            }
        }
    }

    /**
     * Handle touchpad input
     */
    fun handleTouchpadInput(x: Float, y: Float, action: Int) {
        viewModelScope.launch {
            try {
                minimapService.handleTouchpadInput(x, y, action)
                Log.d(TAG, "Touchpad input handled: x=$x, y=$y, action=$action")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to handle touchpad input", e)
            }
        }
    }


    /**
     * Get the minimap service instance
     */
    fun getMinimapService(): MinimapService {
        return minimapService
    }

    /**
     * Stop minimap service
     */
    fun stopMinimapService() {
        viewModelScope.launch {
            try {
                minimapService.stopMinimapService()
                _isMinimapEnabled.value = false
                Log.d(TAG, "Minimap service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop minimap service", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopMinimapService()
    }
}

/**
 * Minimap settings data class
 */
@kotlinx.serialization.Serializable
data class MinimapSettings(
    val zoomLevel: Float = 1.0f, // 1.0 = 100m radius, 2.0 = 200m radius, etc.
    val orientation: MinimapOrientation = MinimapOrientation.NORTH_UP,
    val size: MinimapSize = MinimapSize.MEDIUM,
    val position: MinimapPosition = MinimapPosition.BOTTOM_RIGHT,
    val features: Set<MinimapFeature> = setOf(
        MinimapFeature.PEERS,
        MinimapFeature.WAYPOINTS,
        MinimapFeature.GRID,
        MinimapFeature.NORTH_INDICATOR
    )
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MinimapSettings) return false
        
        return zoomLevel == other.zoomLevel &&
                orientation == other.orientation &&
                size == other.size &&
                position == other.position &&
                features == other.features
    }
    
    override fun hashCode(): Int {
        var result = zoomLevel.hashCode()
        result = 31 * result + orientation.hashCode()
        result = 31 * result + size.hashCode()
        result = 31 * result + position.hashCode()
        result = 31 * result + features.hashCode()
        return result
    }
}

/**
 * Minimap orientation modes
 */
@kotlinx.serialization.Serializable
enum class MinimapOrientation {
    NORTH_UP,           // North always up (rotating)
    HEADING_UP,         // User direction always up (fixed)
    AUTO                // Switch based on movement
}

/**
 * Minimap sizes
 */
@kotlinx.serialization.Serializable
enum class MinimapSize {
    SMALL,              // 150x150 pixels
    MEDIUM,             // 200x200 pixels
    LARGE               // 250x250 pixels
}

/**
 * Minimap positions
 */
@kotlinx.serialization.Serializable
enum class MinimapPosition {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    CENTER
}

/**
 * Minimap features
 */
@kotlinx.serialization.Serializable
enum class MinimapFeature {
    PEERS,              // Show peer locations
    WAYPOINTS,          // Show waypoints
    GRID,               // Show grid lines
    NORTH_INDICATOR,    // Show north indicator
    DISTANCE_RINGS,     // Show distance rings
    COMPASS_QUALITY,    // Show compass quality
    BATTERY_LEVEL,      // Show battery level
    NETWORK_STATUS      // Show network status
}
