package com.tak.lite.ui.map

import android.util.Log

/**
 * Configuration for native MapLibre clustering
 */
data class ClusteringConfig(
    val clusterRadius: Int = 100,
    val enablePeerClustering: Boolean = true,
    val enablePoiClustering: Boolean = true,
    val enableLineClustering: Boolean = true,
    val peerClusterMaxZoom: Int = 9,
    val poiClusterMaxZoom: Int = 9,
    val lineClusterMaxZoom: Int = 9
) {
    companion object {
        private const val TAG = "ClusteringConfig"
        
        /**
         * Get default configuration
         */
        fun getDefault(): ClusteringConfig {
            val config = ClusteringConfig()
            Log.d(TAG, "Created default clustering config: $config")
            return config
        }
    }
    
    override fun toString(): String {
        return "ClusteringConfig(clusterRadius=$clusterRadius, enablePeerClustering=$enablePeerClustering, enablePoiClustering=$enablePoiClustering, enableLineClustering=$enableLineClustering, peerClusterMaxZoom=$peerClusterMaxZoom, poiClusterMaxZoom=$poiClusterMaxZoom, lineClusterMaxZoom=$lineClusterMaxZoom)"
    }
} 