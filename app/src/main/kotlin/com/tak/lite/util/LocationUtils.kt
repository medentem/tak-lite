package com.tak.lite.util

import android.content.Context
import android.util.Log
import com.tak.lite.util.CoordinateUtils.calculateBearing
import java.util.Locale

/**
 * Utility class for location-based calculations and relative positioning
 */
object LocationUtils {
    private const val TAG = "LocationUtils"
    
    // Threshold for "your location" in meters
    private const val YOUR_LOCATION_THRESHOLD_METERS = 8046.72 // 5 miles in meters
    
    /**
     * Convert bearing degrees to cardinal direction (N, NE, E, SE, S, SW, W, NW)
     */
    fun bearingToCardinalDirection(bearing: Double): String {
        return when {
            bearing >= 337.5 || bearing < 22.5 -> "N"
            bearing >= 22.5 && bearing < 67.5 -> "NE"
            bearing >= 67.5 && bearing < 112.5 -> "E"
            bearing >= 112.5 && bearing < 157.5 -> "SE"
            bearing >= 157.5 && bearing < 202.5 -> "S"
            bearing >= 202.5 && bearing < 247.5 -> "SW"
            bearing >= 247.5 && bearing < 292.5 -> "W"
            bearing >= 292.5 && bearing < 337.5 -> "NW"
            else -> "N"
        }
    }
    
    /**
     * Get relative location description for weather forecast
     * @param forecastLat Forecast latitude
     * @param forecastLon Forecast longitude
     * @param userLat User's current latitude
     * @param userLon User's current longitude
     * @return Description like "Forecast for your location" or "Forecast for 12.3 mi. NW of your location"
     */
    fun getRelativeLocationDescription(
        forecastLat: Double, 
        forecastLon: Double, 
        userLat: Double, 
        userLon: Double,
        context: Context,
        prefix: String = ""
    ): String {
        // Validate coordinates
        if (!isValidCoordinates(forecastLat, forecastLon) || !isValidCoordinates(userLat, userLon)) {
            Log.w(TAG, "Invalid coordinates provided for relative location calculation")
            return "Unknown distance from you"
        }
        
        val distanceMeters = haversine(userLat, userLon, forecastLat, forecastLon)
        
        Log.d(TAG, "Distance from user: ${String.format("%.1f", distanceMeters)} meters")
        
        return if (distanceMeters <= YOUR_LOCATION_THRESHOLD_METERS) {
            "${prefix}your location".replaceFirstChar { if (it. isLowerCase()) it. titlecase(Locale.getDefault()) else it. toString() }
        } else {
            val bearing = calculateBearing(userLat, userLon, forecastLat, forecastLon)
            val direction = bearingToCardinalDirection(bearing)
            val formattedDistance = UnitManager.metersToDistanceShort(distanceMeters, context)
            "${prefix}$formattedDistance $direction of your location"
        }
    }
    
    /**
     * Validate coordinates are within reasonable bounds
     */
    private fun isValidCoordinates(lat: Double, lon: Double): Boolean {
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }
}
