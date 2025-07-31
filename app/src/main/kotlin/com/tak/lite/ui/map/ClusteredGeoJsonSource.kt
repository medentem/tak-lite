package com.tak.lite.ui.map

import android.util.Log
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * Helper class for creating clustered GeoJSON sources in MapLibre
 * Supports native clustering with configurable parameters
 */
class ClusteredGeoJsonSource(
    private val sourceId: String,
    private val clusterRadius: Int,
    private val clusterMaxZoom: Int,
) {
    
    companion object {
        private const val TAG = "ClusteredGeoJsonSource"
    }

    /**
     * Create a GeoJSON source with clustering enabled and initial data
     */
    fun createSourceWithData(geoJson: String): GeoJsonSource {
        Log.d(TAG, "Creating clustered source with data: $sourceId with radius=$clusterRadius, maxZoom=$clusterMaxZoom")
        Log.d(TAG, "GeoJSON data length: ${geoJson.length} characters")
        
        val options = GeoJsonOptions()
            .withCluster(true)
            .withClusterRadius(clusterRadius)
            .withClusterMaxZoom(clusterMaxZoom)
        
        Log.d(TAG, "Created GeoJsonOptions: cluster=${options["cluster"]}, clusterRadius=${options["clusterRadius"]}, clusterMaxZoom=${options["clusterMaxZoom"]}")
        
        // Debug: Log the actual option values
        Log.d(TAG, "GeoJsonOptions details:")
        Log.d(TAG, "  cluster: ${options["cluster"]}")
        Log.d(TAG, "  clusterRadius: ${options["clusterRadius"]}")
        Log.d(TAG, "  clusterMaxZoom: ${options["clusterMaxZoom"]}")
        
        val source = GeoJsonSource(sourceId, geoJson, options)
        Log.d(TAG, "Created GeoJsonSource: ${source.id}")
        return source
    }
} 