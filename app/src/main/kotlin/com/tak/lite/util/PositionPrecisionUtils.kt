package com.tak.lite.util

import kotlin.math.roundToInt

/**
 * Utility class for handling position precision conversion according to Meshtastic documentation.
 * Position precision is a value from 0-32 that determines how precise location data is shared.
 * 0 means no location data is sent, 32 means full precision.
 */
object PositionPrecisionUtils {
    /**
     * Converts a position precision value (0-32) to its corresponding distance in feet.
     * Returns null for 0 (no location data) and 32 (full precision).
     */
    fun getPrecisionInFeet(precision: Int): Int? {
        return when (precision) {
            0 -> null // Location data never sent
            32 -> 0 // Full precision
            10 -> 4787 // 14.5 miles
            11 -> 2392 // 7.3 miles
            12 -> 1194 // 3.6 miles
            13 -> 597 // 1.8 miles
            14 -> 299 // 4787 feet
            15 -> 148 // 2392 feet
            16 -> 75 // 1194 feet
            17 -> 37 // 597 feet
            18 -> 19 // 299 feet
            19 -> 9 // 148 feet
            else -> null // Unknown precision value
        }
    }

    /**
     * Formats a position precision value for display in the UI.
     * Converts feet to miles if the distance is greater than or equal to 1 mile.
     * Returns null for invalid precision values or when location data is not sent.
     */
    fun formatPrecision(precision: Int): String? {
        val feet = getPrecisionInFeet(precision) ?: return null
        return if (feet >= 5280) { // 1 mile = 5280 feet
            val miles = (feet / 5280.0).roundToInt()
            "Location precision: $miles mi"
        } else if (feet == 0) {
            "Location precision: Precise"
        } else {
            "Location precision: $feet ft"
        }
    }
} 