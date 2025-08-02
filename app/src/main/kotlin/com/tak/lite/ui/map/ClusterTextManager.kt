package com.tak.lite.ui.map

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.maplibre.android.maps.MapLibreMap

/**
 * Optimized manager for cluster text labels for both peer and POI clusters
 * Handles extracting cluster information from GL layers and updating overlay text
 * with performance optimizations for smooth panning
 */
class ClusterTextManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "ClusterTextManager"
        private const val CLUSTER_UPDATE_INTERVAL_MS = 100L // Increased responsiveness (10fps)
        private const val CAMERA_MOVE_THROTTLE_MS = 50L // Faster updates during movement
        private const val MAX_CLUSTERS_PER_UPDATE = 30 // Limit clusters per update
        
        // Temporary flag to disable cluster text manager
        private const val CLUSTER_TEXT_ENABLED = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateClusterTexts()
            // Use faster updates during camera movement for smooth label following
            val interval = if (isCameraMoving) CAMERA_MOVE_THROTTLE_MS else CLUSTER_UPDATE_INTERVAL_MS
            handler.postDelayed(this, interval)
        }
    }

    private var isActive = false
    private var clusterTextOverlayView: ClusterTextOverlayView? = null
    
    // Performance optimization: Track camera movement
    private var isCameraMoving = false
    private var lastCameraMoveTime = 0L
    private var cameraMoveHandler = Handler(Looper.getMainLooper())
    private val cameraMoveRunnable = Runnable { 
        isCameraMoving = false 
        Log.d(TAG, "Camera movement ended, switching to normal updates")
        
        // Restart the update loop with normal intervals
        handler.removeCallbacks(updateRunnable)
        handler.post(updateRunnable)
    }

    /**
     * Set the cluster text overlay view
     */
    fun setClusterTextOverlayView(overlayView: ClusterTextOverlayView?) {
        if (!CLUSTER_TEXT_ENABLED) {
            return
        }
        
        this.clusterTextOverlayView = overlayView
        if (overlayView != null && !isActive) {
            startUpdates()
        } else if (overlayView == null && isActive) {
            stopUpdates()
        }
    }

    /**
     * Notify that camera is moving (called from external camera listeners)
     */
    fun onCameraMoving() {
        if (!CLUSTER_TEXT_ENABLED) {
            return
        }
        
        val now = System.currentTimeMillis()
        if (!isCameraMoving) {
            isCameraMoving = true
            Log.d(TAG, "Camera movement detected, switching to fast updates")
            
            // Restart the update loop with faster intervals
            handler.removeCallbacks(updateRunnable)
            handler.post(updateRunnable)
        }
        
        // Reset the camera move timer
        lastCameraMoveTime = now
        cameraMoveHandler.removeCallbacks(cameraMoveRunnable)
        cameraMoveHandler.postDelayed(cameraMoveRunnable, CAMERA_MOVE_THROTTLE_MS)
    }

    /**
     * Start cluster text updates
     */
    fun startUpdates() {
        if (!CLUSTER_TEXT_ENABLED) {
            return
        }
        
        if (isActive) return
        isActive = true
        handler.post(updateRunnable)
        Log.d(TAG, "Started cluster text updates")
    }

    /**
     * Stop cluster text updates
     */
    fun stopUpdates() {
        if (!isActive) return
        isActive = false
        handler.removeCallbacks(updateRunnable)
        cameraMoveHandler.removeCallbacks(cameraMoveRunnable)
        Log.d(TAG, "Stopped cluster text updates")
    }

    /**
     * Update cluster texts from GL layers with performance optimizations
     */
    private fun updateClusterTexts() {
        if (!CLUSTER_TEXT_ENABLED) {
            return
        }
        
        // Don't skip updates during camera movement - labels need to stay synchronized
        // Instead, optimize the query area and frequency
        
        try {
            mapLibreMap.getStyle { style ->
                // Get peer clusters with limit
                val peerClusters = getClusterFeatures(
                    ClusteredLayerManager.PEER_CLUSTERS_LAYER,
                    ClusterTextOverlayView.ClusterType.PEER
                ).take(MAX_CLUSTERS_PER_UPDATE)

                // Get POI clusters with limit
                val poiClusters = getClusterFeatures(
                    ClusteredLayerManager.POI_CLUSTERS_LAYER,
                    ClusterTextOverlayView.ClusterType.POI
                ).take(MAX_CLUSTERS_PER_UPDATE)

                // Update overlay view immediately
                clusterTextOverlayView?.let { overlay ->
                    overlay.updatePeerClusters(peerClusters)
                    overlay.updatePoiClusters(poiClusters)
                }

                if (peerClusters.isNotEmpty() || poiClusters.isNotEmpty()) {
                    Log.d(TAG, "Updated cluster texts: ${peerClusters.size} peer clusters, ${poiClusters.size} POI clusters")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cluster texts", e)
        }
    }

    /**
     * Get cluster features from a specific layer with performance optimizations
     */
    private fun getClusterFeatures(layerId: String, clusterType: ClusterTextOverlayView.ClusterType): List<ClusterTextOverlayView.ClusterFeature> {
        val clusters = mutableListOf<ClusterTextOverlayView.ClusterFeature>()
        
        try {
            // Use a smaller query area during camera movement for better performance
            val queryArea = if (isCameraMoving) {
                // Query only center area during movement
                val centerX = mapLibreMap.width / 2f
                val centerY = mapLibreMap.height / 2f
                val size = minOf(mapLibreMap.width, mapLibreMap.height) / 2f
                android.graphics.RectF(
                    centerX - size,
                    centerY - size,
                    centerX + size,
                    centerY + size
                )
            } else {
                // Query full screen when stationary
                android.graphics.RectF(0f, 0f, mapLibreMap.width.toFloat(), mapLibreMap.height.toFloat())
            }

            // Query features with the optimized area
            val features = mapLibreMap.queryRenderedFeatures(queryArea, layerId)

            for (feature in features) {
                val pointCount = feature.getNumberProperty("point_count")?.toInt()
                if (pointCount != null && pointCount > 1) {
                    val geometry = feature.geometry()
                    if (geometry is org.maplibre.geojson.Point) {
                        val clusterId = feature.getStringProperty("cluster_id") ?: "cluster_${clusters.size}"
                        clusters.add(
                            ClusterTextOverlayView.ClusterFeature(
                                clusterId = clusterId,
                                position = geometry,
                                pointCount = pointCount,
                                clusterType = clusterType
                            )
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting cluster features for layer $layerId", e)
        }

        return clusters
    }

    /**
     * Force an immediate update (for external triggers)
     */
    fun forceUpdate() {
        clusterTextOverlayView?.forceUpdate()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        stopUpdates()
        clusterTextOverlayView = null
        Log.d(TAG, "Cluster text manager cleaned up")
    }
} 