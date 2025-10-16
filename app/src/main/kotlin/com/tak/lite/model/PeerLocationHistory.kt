package com.tak.lite.model

import kotlinx.serialization.Serializable
import org.maplibre.android.geometry.LatLng

@Serializable
data class PeerLocationEntry(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    // Additional position data from Meshtastic position packets
    val gpsTimestamp: Long? = null, // GPS timestamp from position packet
    val groundSpeed: Double? = null, // Ground speed in m/s
    val groundTrack: Double? = null, // Ground track/heading in degrees
    val altitude: Int? = null, // Altitude in meters above MSL
    val altitudeHae: Int? = null, // Height Above Ellipsoid in meters
    val gpsAccuracy: Int? = null, // GPS accuracy in mm
    val fixQuality: Int? = null, // GPS fix quality
    val fixType: Int? = null, // GPS fix type (2D/3D)
    val satellitesInView: Int? = null, // Number of satellites in view
    val pdop: Int? = null, // Position Dilution of Precision
    val hdop: Int? = null, // Horizontal Dilution of Precision
    val vdop: Int? = null, // Vertical Dilution of Precision
    val locationSource: Int? = null, // How location was acquired (manual, GPS, external)
    val altitudeSource: Int? = null, // How altitude was acquired
    val sequenceNumber: Int? = null, // Position sequence number
    val precisionBits: Int? = null, // Precision bits set by sending node
    val userStatus: UserStatus? = null // User status color
) {
    fun toLatLng(): LatLng = LatLng(latitude, longitude)

    fun toLatLngSerializable(): LatLngSerializable = LatLngSerializable(latitude, longitude)
    
    /**
     * Get the most accurate timestamp available
     * Prefer GPS timestamp if available, otherwise use app timestamp
     */
    fun getBestTimestamp(): Long = gpsTimestamp ?: timestamp
    
    /**
     * Check if this entry has velocity data (speed and track)
     */
    fun hasVelocityData(): Boolean = groundSpeed != null && groundTrack != null
    
    /**
     * Get velocity as a pair of (speed in m/s, heading in degrees)
     */
    fun getVelocity(): Pair<Double, Double>? {
        return if (hasVelocityData()) {
            Pair(groundSpeed!!, groundTrack!!)
        } else null
    }
    
    /**
     * Check if this entry has GPS quality data
     */
    fun hasGpsQualityData(): Boolean = 
        gpsAccuracy != null || fixQuality != null || fixType != null || 
        satellitesInView != null || pdop != null || hdop != null || vdop != null
}

@Serializable
data class PeerLocationHistory(
    val peerId: String,
    val entries: List<PeerLocationEntry> = emptyList(),
    val maxEntries: Int = 100 // Keep last 100 entries
) {
    fun addEntry(entry: PeerLocationEntry): PeerLocationHistory {
        // Add new entry and sort by timestamp to ensure chronological order
        val newEntries = (entries + entry)
            .sortedBy { it.timestamp } // CRITICAL FIX: Sort by timestamp
            .takeLast(maxEntries)
        return copy(entries = newEntries)
    }
    
    fun getRecentEntries(minutes: Int): List<PeerLocationEntry> {
        val cutoffTime = System.currentTimeMillis() - (minutes * 60 * 1000L)
        // Filter and ensure chronological order for prediction engine
        return entries
            .filter { it.timestamp >= cutoffTime }
            .sortedBy { it.timestamp } // CRITICAL FIX: Ensure chronological order
    }
    
    fun getLatestEntry(): PeerLocationEntry? = entries.lastOrNull()
        
    /**
     * Validate that entries are in chronological order
     * This is a safety check to catch any data corruption
     */
    fun validateChronologicalOrder(): Boolean {
        if (entries.size <= 1) return true
        for (i in 1 until entries.size) {
            if (entries[i].timestamp < entries[i-1].timestamp) {
                return false
            }
        }
        return true
    }
}