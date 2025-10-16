package com.tak.lite.vuzix

import android.content.Context
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.network.MeshNetworkService
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
    private val vuzixManager: VuzixManager
) {
    companion object {
        private const val TAG = "MinimapService"
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main)

    /**
     * Start minimap service
     */
    fun startMinimapService() {
        coroutineScope.launch {
            // Combine all TAK-Lite data streams
            combine(
                meshNetworkService.bestLocation,
                meshNetworkService.peerLocations
            ) { userLocation: LatLng?, peerLocations: Map<String, PeerLocationEntry> ->
                MinimapData(
                    userLocation = userLocation?.let { 
                        LatLngSerializable(it.latitude, it.longitude)
                    },
                    userHeading = null, // TODO: Get heading from alternative source
                    peers = peerLocations,
                    annotations = emptyList(), // TODO: Get annotations from alternative source
                    compassQuality = null, // TODO: Get compass quality from alternative source
                    headingSource = null // TODO: Get heading source from alternative source
                )
            }.onEach { minimapData ->
                updateMinimap(minimapData)
            }.launchIn(coroutineScope)
        }
    }

    /**
     * Update minimap with new data
     */
    private fun updateMinimap(data: MinimapData) {
        if (data.userLocation == null) return

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

        // Update Vuzix minimap
        vuzixManager.updateMinimap(
            userLocation = data.userLocation,
            userHeading = data.userHeading,
            peers = data.peers,
            annotations = waypoints
        )
    }

    /**
     * Show minimap
     */
    fun showMinimap() {
        vuzixManager.setMinimapVisible(true)
    }

    /**
     * Hide minimap
     */
    fun hideMinimap() {
        vuzixManager.setMinimapVisible(false)
    }

    /**
     * Toggle minimap visibility
     */
    fun toggleMinimap() {
        vuzixManager.toggleMinimap()
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
