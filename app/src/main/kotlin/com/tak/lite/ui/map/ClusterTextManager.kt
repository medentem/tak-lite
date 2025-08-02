package com.tak.lite.ui.map

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection

/**
 * Manages cluster text labels for both peer and POI clusters
 * Handles extracting cluster information from GL layers and updating overlay text
 */
class ClusterTextManager(private val mapLibreMap: MapLibreMap) {
    companion object {
        private const val TAG = "ClusterTextManager"
        private const val CLUSTER_UPDATE_INTERVAL_MS = 500L // Update every 500ms
    }

    private val handler = Handler(Looper.getMainLooper())
    private val updateRunnable = object : Runnable {
        override fun run() {
            updateClusterTexts()
            handler.postDelayed(this, CLUSTER_UPDATE_INTERVAL_MS)
        }
    }

    private var isActive = false
    private var clusterTextOverlayView: ClusterTextOverlayView? = null

    /**
     * Set the cluster text overlay view
     */
    fun setClusterTextOverlayView(overlayView: ClusterTextOverlayView?) {
        this.clusterTextOverlayView = overlayView
        if (overlayView != null && !isActive) {
            startUpdates()
        } else if (overlayView == null && isActive) {
            stopUpdates()
        }
    }

    /**
     * Start cluster text updates
     */
    fun startUpdates() {
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
        Log.d(TAG, "Stopped cluster text updates")
    }

    /**
     * Update cluster texts from GL layers
     */
    private fun updateClusterTexts() {
        try {
            mapLibreMap.getStyle { style ->
                // Get peer clusters
                val peerClusters = getClusterFeatures(
                    ClusteredLayerManager.PEER_CLUSTERS_LAYER,
                    ClusterTextOverlayView.ClusterType.PEER
                )

                // Get POI clusters
                val poiClusters = getClusterFeatures(
                    ClusteredLayerManager.POI_CLUSTERS_LAYER,
                    ClusterTextOverlayView.ClusterType.POI
                )

                // Update overlay view
                clusterTextOverlayView?.let { overlay ->
                    overlay.updatePeerClusters(peerClusters)
                    overlay.updatePoiClusters(poiClusters)
                }

                Log.d(TAG, "Updated cluster texts: ${peerClusters.size} peer clusters, ${poiClusters.size} POI clusters")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating cluster texts", e)
        }
    }

    /**
     * Get cluster features from a specific layer
     */
    private fun getClusterFeatures(layerId: String, clusterType: ClusterTextOverlayView.ClusterType): List<ClusterTextOverlayView.ClusterFeature> {
        val clusters = mutableListOf<ClusterTextOverlayView.ClusterFeature>()
        
        try {
            // Query all features in the cluster layer
            val features = mapLibreMap.queryRenderedFeatures(
                android.graphics.RectF(0f, 0f, mapLibreMap.width.toFloat(), mapLibreMap.height.toFloat()),
                layerId
            )

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
     * Clean up resources
     */
    fun cleanup() {
        stopUpdates()
        clusterTextOverlayView = null
        Log.d(TAG, "Cluster text manager cleaned up")
    }
} 