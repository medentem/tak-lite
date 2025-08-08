package com.tak.lite.util

import kotlin.math.*

/**
 * Utility class for standardized coordinate calculations
 * Prevents inconsistencies between degrees and radians usage
 */
object CoordinateUtils {
    private const val EARTH_RADIUS_METERS = 6378137.0
    private const val DEG_TO_RAD = PI / 180.0
    private const val RAD_TO_DEG = 180.0 / PI
    
    /**
     * Convert degrees to radians
     */
    fun toRadians(degrees: Double): Double = degrees * DEG_TO_RAD
    
    /**
     * Convert radians to degrees
     */
    fun toDegrees(radians: Double): Double = radians * RAD_TO_DEG
    
    /**
     * Calculate new position given current position, distance, and bearing
     * All calculations use radians internally for precision
     */
    fun calculateNewPosition(
        currentLat: Double, 
        currentLon: Double, 
        distanceMeters: Double, 
        bearingRadians: Double
    ): Pair<Double, Double> {
        val latRad = toRadians(currentLat)
        val lonRad = toRadians(currentLon)
        val angularDistance = distanceMeters / EARTH_RADIUS_METERS
        
        val newLatRad = asin(
            sin(latRad) * cos(angularDistance) + 
            cos(latRad) * sin(angularDistance) * cos(bearingRadians)
        )
        
        val newLonRad = lonRad + atan2(
            sin(bearingRadians) * sin(angularDistance) * cos(latRad),
            cos(angularDistance) - sin(latRad) * sin(newLatRad)
        )
        
        return Pair(toDegrees(newLatRad), toDegrees(newLonRad))
    }
    
    /**
     * Calculate distance between two points using optimized haversine from MapUtils
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        return haversine(lat1, lon1, lat2, lon2)
    }
    
    /**
     * Calculate bearing between two points
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = toRadians(lat1)
        val lon1Rad = toRadians(lon1)
        val lat2Rad = toRadians(lat2)
        val lon2Rad = toRadians(lon2)
        
        val dLon = lon2Rad - lon1Rad
        
        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        
        val bearing = atan2(y, x)
        return (toDegrees(bearing) + 360.0) % 360.0
    }
    
    /**
     * Generate random position within radius of center point
     * Uses square root of random for uniform distribution over area
     */
    fun randomPositionInRadius(
        centerLat: Double, 
        centerLon: Double, 
        radiusMeters: Double
    ): Pair<Double, Double> {
        val rawRandom = kotlin.random.Random.nextDouble()
        val distance = sqrt(rawRandom) * radiusMeters
        val angle = kotlin.random.Random.nextDouble() * 2 * PI
        
        return calculateNewPosition(centerLat, centerLon, distance, angle)
    }
}
