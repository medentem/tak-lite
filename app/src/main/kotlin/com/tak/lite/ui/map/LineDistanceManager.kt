package com.tak.lite.ui.map

import android.content.Context
import android.util.Log
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.haversine
import org.maplibre.android.maps.MapLibreMap

/**
 * Manages distance labels for line annotations using hybrid overlay approach.
 * Handles distance calculations, filtering based on minimum distance settings,
 * and updates the overlay view with distance labels.
 */
class LineDistanceManager(
    private val mapLibreMap: MapLibreMap,
    private val context: Context
) {
    companion object {
        private const val TAG = "LineDistanceManager"
    }

    private var currentDistanceFeatures: List<DistanceFeature> = emptyList()
    
    // Callback for distance text overlay updates
    interface DistanceTextOverlayCallback {
        fun updateDistanceTexts(distanceFeatures: List<DistanceFeature>)
    }
    
    private var distanceTextCallback: DistanceTextOverlayCallback? = null
    
    fun setDistanceTextCallback(callback: DistanceTextOverlayCallback?) {
        distanceTextCallback = callback
    }

    /**
     * Update distance features for the given lines
     */
    fun updateDistanceFeatures(lines: List<MapAnnotation.Line>) {
        try {
            val distanceFeatures = mutableListOf<DistanceFeature>()
            val minDistMeters = getMinimumDistanceSetting()
            
            lines.forEach { line ->
                // Calculate distances for line segments
                val segmentDistances = calculateSegmentDistances(line)
                
                // Filter segments that meet the minimum distance requirement
                segmentDistances.forEach { distanceLabel ->
                    if (distanceLabel.distanceMeters >= minDistMeters) {
                        val distanceFeature = DistanceFeature(
                            lineId = line.id,
                            segmentIndex = distanceLabel.segmentIndex,
                            distanceMeters = distanceLabel.distanceMeters,
                            midpoint = distanceLabel.midpoint
                        )
                        distanceFeatures.add(distanceFeature)
                        Log.d(TAG, "Added distance feature for line ${line.id} segment ${distanceLabel.segmentIndex}: ${distanceLabel.distanceMeters} m")
                    } else {
                        Log.d(TAG, "Skipped distance feature for line ${line.id} segment ${distanceLabel.segmentIndex}: ${distanceLabel.distanceMeters} m < min: ${minDistMeters} m")
                    }
                }
            }
            
            currentDistanceFeatures = distanceFeatures
            
            // Notify text overlay callback
            distanceTextCallback?.updateDistanceTexts(distanceFeatures)
            
            Log.d(TAG, "Updated distance features: ${distanceFeatures.size} labels")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error updating distance features", e)
        }
    }

    /**
     * Calculate distances for line segments
     */
    private fun calculateSegmentDistances(line: MapAnnotation.Line): List<DistanceLabel> {
        return line.points.zipWithNext { point1, point2 ->
            val distanceMeters = haversine(
                point1.lt, point1.lng,
                point2.lt, point2.lng
            )
                    DistanceLabel(
            segmentIndex = line.points.indexOf(point1),
            distanceMeters = distanceMeters,
                midpoint = com.tak.lite.model.LatLngSerializable(
                    (point1.lt + point2.lt) / 2,
                    (point1.lng + point2.lng) / 2
                )
            )
        }
    }

    /**
     * Get minimum distance setting from SharedPreferences
     */
    private fun getMinimumDistanceSetting(): Float {
        return try {
            val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
            val minDistMiles = prefs.getFloat("min_line_segment_dist_miles", 1.0f)
            // Convert miles to meters
            minDistMiles * 1609.344f
        } catch (e: Exception) {
            Log.w(TAG, "Could not access SharedPreferences, using default minimum distance: 1609.344f meters", e)
            1609.344f
        }
    }

    /**
     * Clear all distance features
     */
    fun clearDistanceFeatures() {
        currentDistanceFeatures = emptyList()
        distanceTextCallback?.updateDistanceTexts(emptyList())
        Log.d(TAG, "Cleared distance features")
    }
}
