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
            val minDistMiles = getMinimumDistanceSetting()
            
            lines.forEach { line ->
                // Calculate distances for line segments
                val segmentDistances = calculateSegmentDistances(line)
                
                // Filter segments that meet the minimum distance requirement
                segmentDistances.forEach { distanceLabel ->
                    if (distanceLabel.distanceMiles >= minDistMiles) {
                        val distanceFeature = DistanceFeature(
                            lineId = line.id,
                            segmentIndex = distanceLabel.segmentIndex,
                            distanceMiles = distanceLabel.distanceMiles,
                            midpoint = distanceLabel.midpoint
                        )
                        distanceFeatures.add(distanceFeature)
                        Log.d(TAG, "Added distance feature for line ${line.id} segment ${distanceLabel.segmentIndex}: ${distanceLabel.distanceMiles} mi")
                    } else {
                        Log.d(TAG, "Skipped distance feature for line ${line.id} segment ${distanceLabel.segmentIndex}: ${distanceLabel.distanceMiles} mi < min: ${minDistMiles} mi")
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
            val distanceMiles = distanceMeters / 1609.344

            DistanceLabel(
                segmentIndex = line.points.indexOf(point1),
                distanceMiles = distanceMiles,
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
            prefs.getFloat("min_line_segment_dist_miles", 1.0f)
        } catch (e: Exception) {
            Log.w(TAG, "Could not access SharedPreferences, using default minimum distance: 1.0f", e)
            1.0f
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
