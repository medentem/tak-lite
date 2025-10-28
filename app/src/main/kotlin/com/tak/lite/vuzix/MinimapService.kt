package com.tak.lite.vuzix

import android.content.Context
import android.util.Log
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.network.MeshNetworkService
import com.tak.lite.repository.AnnotationRepository
import com.tak.lite.repository.LocationRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Minimap Service for Vuzix Z100 Smart Glasses
 * Integrates with TAK-Lite data streams to provide real-time minimap updates
 */
@Singleton
class MinimapService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val meshNetworkService: MeshNetworkService,
    private val annotationRepository: AnnotationRepository,
    private val locationRepository: LocationRepository,
    private val vuzixManager: VuzixManager
) {
    companion object {
        private const val TAG = "MinimapService"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * Get VuzixManager instance for monitoring connection state
     */
    fun getVuzixManager(): VuzixManager {
        return vuzixManager
    }

    /**
     * Start minimap service
     */
    fun startMinimapService() {
        coroutineScope.launch {
            try {
                Log.d(TAG, "=== MINIMAP SERVICE STARTING ===")
                Log.d(TAG, "Starting sensor tracking...")
                
                // Start sensor tracking for heading data
                locationRepository.startSensorTracking()
                
                Log.d(TAG, "Starting data stream combination...")
                
                // Combine all TAK-Lite data streams
                combine(
                    meshNetworkService.bestLocation,
                    meshNetworkService.peerLocations,
                    annotationRepository.annotations,
                    locationRepository.headingData
                ) { userLocation: LatLng?, peerLocations: Map<String, PeerLocationEntry>, annotations: List<MapAnnotation>, headingData: com.tak.lite.ui.location.DirectionOverlayData ->
                    MinimapData(
                        userLocation = userLocation?.let { 
                            LatLngSerializable(it.latitude, it.longitude)
                        },
                        userHeading = headingData.headingDegrees,
                        peers = peerLocations,
                        annotations = annotations,
                        compassQuality = headingData.compassQuality,
                        headingSource = headingData.headingSource
                    )
                }.onEach { minimapData ->
                    Log.d(TAG, "Minimap data received - updating minimap...")
                    Log.d(TAG, "User location: ${minimapData.userLocation}")
                    Log.d(TAG, "User heading: ${minimapData.userHeading}")
                    Log.d(TAG, "Peers count: ${minimapData.peers.size}")
                    Log.d(TAG, "Annotations count: ${minimapData.annotations.size}")
                    updateMinimap(minimapData)
                }.launchIn(coroutineScope)
                
                Log.d(TAG, "✅ Minimap service started successfully")
                Log.d(TAG, "Data streams are now active and monitoring")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to start minimap service", e)
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                Log.e(TAG, "Exception message: ${e.message}")
            }
        }
    }

    /**
     * Update minimap with new data
     */
    private fun updateMinimap(data: MinimapData) {
        Log.d(TAG, "=== UPDATING MINIMAP ===")
        Log.d(TAG, "User location: ${data.userLocation}")
        Log.d(TAG, "User heading: ${data.userHeading}")
        Log.d(TAG, "Peers: ${data.peers.size}")
        Log.d(TAG, "Annotations: ${data.annotations.size}")
        
        if (data.userLocation == null) {
            Log.w(TAG, "⚠️ User location is null - skipping minimap update")
            return
        }

        // Convert annotations to waypoints
        val waypoints = data.annotations
            .filterIsInstance<MapAnnotation.PointOfInterest>()
            .map { poi ->
                MinimapWaypoint(
                    id = poi.id,
                    position = poi.position,
                    label = poi.label,
                    color = poi.color.name,
                    shape = poi.shape.name
                )
            }

        Log.d(TAG, "Converted ${waypoints.size} annotations to waypoints")

        // Update Vuzix minimap
        Log.d(TAG, "Calling vuzixManager.updateMinimap()...")
        vuzixManager.updateMinimap(
            userLocation = data.userLocation,
            userHeading = data.userHeading,
            peers = data.peers,
            annotations = waypoints
        )
        
        Log.d(TAG, "✅ Minimap update completed")
    }

    /**
     * Show minimap
     */
    fun showMinimap() {
        Log.d(TAG, "=== SHOWING MINIMAP ===")
        Log.d(TAG, "Calling vuzixManager.setMinimapVisible(true)")
        vuzixManager.setMinimapVisible(true)
        Log.d(TAG, "✅ Minimap show command sent")
    }

    /**
     * Hide minimap
     */
    fun hideMinimap() {
        Log.d(TAG, "=== HIDING MINIMAP ===")
        Log.d(TAG, "Calling vuzixManager.setMinimapVisible(false)")
        vuzixManager.setMinimapVisible(false)
        Log.d(TAG, "✅ Minimap hide command sent")
    }

    /**
     * Toggle minimap visibility
     */
    fun toggleMinimap() {
        Log.d(TAG, "=== TOGGLING MINIMAP ===")
        Log.d(TAG, "Calling vuzixManager.toggleMinimap()")
        vuzixManager.toggleMinimap()
        Log.d(TAG, "✅ Minimap toggle command sent")
    }

    /**
     * Handle voice command
     */
    fun handleVoiceCommand(command: String) {
        vuzixManager.handleVoiceCommand(command)
    }

    /**
     * Handle touchpad input
     */
    fun handleTouchpadInput(x: Float, y: Float, action: Int) {
        vuzixManager.handleTouchpadInput(x, y, action)
    }

    /**
     * Stop minimap service
     */
    fun stopMinimapService() {
        locationRepository.stopSensorTracking()
        vuzixManager.disconnect()
    }
}

/**
 * Minimap data container
 */
data class MinimapData(
    val userLocation: LatLngSerializable?,
    val userHeading: Float?,
    val peers: Map<String, PeerLocationEntry>,
    val annotations: List<MapAnnotation>,
    val compassQuality: com.tak.lite.ui.location.CompassQuality?,
    val headingSource: com.tak.lite.ui.location.HeadingSource?
)
