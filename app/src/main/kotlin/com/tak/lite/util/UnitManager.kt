package com.tak.lite.util

import android.content.Context
import android.content.res.Configuration
import java.util.Locale
import android.os.Build
import com.tak.lite.R

/**
 * Manages unit system (imperial/metric) throughout the app.
 * Handles unit conversions and formatting based on user preferences and locale.
 */
object UnitManager {
    
    enum class UnitSystem {
        IMPERIAL, METRIC
    }
    
    private const val PREF_NAME = "unit_prefs"
    private const val KEY_UNIT_SYSTEM = "unit_system"
    
    // Conversion constants
    private const val METERS_TO_MILES = 1609.344
    private const val METERS_TO_FEET = 3.28084
    private const val MPS_TO_MPH = 2.23694
    private const val MPS_TO_KMH = 3.6
    
    /**
     * Get the current unit system from preferences or default based on locale
     */
    fun getUnitSystem(context: Context): UnitSystem {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val systemName = prefs.getString(KEY_UNIT_SYSTEM, null)
        
        return if (systemName != null) {
            UnitSystem.valueOf(systemName)
        } else {
            // Default based on locale
            getDefaultUnitSystemForLocale(context)
        }
    }
    
    /**
     * Set the unit system preference
     */
    fun setUnitSystem(context: Context, unitSystem: UnitSystem) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_UNIT_SYSTEM, unitSystem.name).apply()
    }
    
    /**
     * Get default unit system based on locale
     */
    private fun getDefaultUnitSystemForLocale(context: Context): UnitSystem {
        val locale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.resources.configuration.locales[0]
        } else {
            @Suppress("DEPRECATION")
            context.resources.configuration.locale
        }
        
        return when (locale.country) {
            "US" -> UnitSystem.IMPERIAL
            else -> UnitSystem.METRIC
        }
    }
    
    /**
     * Convert meters to distance string with appropriate unit
     */
    fun metersToDistance(meters: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> {
                val miles = meters / METERS_TO_MILES
                context.getString(R.string.distance_format_imperial, miles)
            }
            UnitSystem.METRIC -> {
                val kilometers = meters / 1000.0
                context.getString(R.string.distance_format_metric, kilometers)
            }
        }
    }
    
    /**
     * Convert meters to distance string with appropriate unit (short format)
     */
    fun metersToDistanceShort(meters: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> {
                val miles = meters / METERS_TO_MILES
                context.getString(R.string.distance_short_format_imperial, miles)
            }
            UnitSystem.METRIC -> {
                val kilometers = meters / 1000.0
                context.getString(R.string.distance_short_format_metric, kilometers)
            }
        }
    }
    
    /**
     * Convert meters per second to speed string with appropriate unit
     */
    fun metersPerSecondToSpeed(mps: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> {
                val mph = mps * MPS_TO_MPH
                context.getString(R.string.speed_format_imperial, mph)
            }
            UnitSystem.METRIC -> {
                val kmh = mps * MPS_TO_KMH
                context.getString(R.string.speed_format_metric, kmh)
            }
        }
    }
    
    /**
     * Convert meters to elevation string with appropriate unit
     */
    fun metersToElevation(meters: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> {
                val feet = meters * METERS_TO_FEET
                context.getString(R.string.elevation_format_imperial, feet)
            }
            UnitSystem.METRIC -> {
                context.getString(R.string.elevation_format_metric, meters)
            }
        }
    }
    
    /**
     * Convert square meters to area string with appropriate unit
     */
    fun squareMetersToArea(sqMeters: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> {
                val sqMiles = sqMeters / (METERS_TO_MILES * METERS_TO_MILES)
                context.getString(R.string.area_format_imperial, sqMiles)
            }
            UnitSystem.METRIC -> {
                val sqKm = sqMeters / 1000000.0
                context.getString(R.string.area_format_metric, sqKm)
            }
        }
    }
    
    /**
     * Get distance unit label
     */
    fun getDistanceUnitLabel(context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_miles)
            UnitSystem.METRIC -> context.getString(R.string.unit_kilometers)
        }
    }
    
    /**
     * Get speed unit label
     */
    fun getSpeedUnitLabel(context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_mph)
            UnitSystem.METRIC -> context.getString(R.string.unit_kmh)
        }
    }
    
    /**
     * Get elevation unit label
     */
    fun getElevationUnitLabel(context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_feet)
            UnitSystem.METRIC -> context.getString(R.string.unit_meters)
        }
    }
    
    /**
     * Get area unit label
     */
    fun getAreaUnitLabel(context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> context.getString(R.string.unit_sq_miles)
            UnitSystem.METRIC -> context.getString(R.string.unit_sq_kilometers)
        }
    }
    
    /**
     * Convert distance to meters (for internal calculations)
     */
    fun distanceToMeters(distance: Double, unitSystem: UnitSystem): Double {
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> distance * METERS_TO_MILES
            UnitSystem.METRIC -> distance * 1000.0
        }
    }
    
    /**
     * Convert speed to meters per second (for internal calculations)
     */
    fun speedToMetersPerSecond(speed: Double, unitSystem: UnitSystem): Double {
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> speed / MPS_TO_MPH
            UnitSystem.METRIC -> speed / MPS_TO_KMH
        }
    }
    
    /**
     * Convert elevation to meters (for internal calculations)
     */
    fun elevationToMeters(elevation: Double, unitSystem: UnitSystem): Double {
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> elevation / METERS_TO_FEET
            UnitSystem.METRIC -> elevation
        }
    }
    
    /**
     * Convert meters per second to speed value only (without unit)
     */
    fun metersPerSecondToSpeedValue(mps: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> (mps * MPS_TO_MPH).toInt().toString()
            UnitSystem.METRIC -> (mps * MPS_TO_KMH).toInt().toString()
        }
    }
    
    /**
     * Convert meters to elevation value only (without unit)
     */
    fun metersToElevationValue(meters: Double, context: Context): String {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> (meters * METERS_TO_FEET).toInt().toString()
            UnitSystem.METRIC -> meters.toInt().toString()
        }
    }
    
    /**
     * Convert miles to the appropriate display unit
     */
    fun milesToDisplayDistance(miles: Double, context: Context): Double {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> miles
            UnitSystem.METRIC -> miles * 1.609344 // miles to kilometers
        }
    }
    
    /**
     * Convert display distance back to miles for storage
     */
    fun displayDistanceToMiles(displayDistance: Double, context: Context): Double {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> displayDistance
            UnitSystem.METRIC -> displayDistance * 0.621371 // kilometers to miles
        }
    }
    
    /**
     * Convert feet to the appropriate display unit for antenna height
     */
    fun feetToDisplayHeight(feet: Double, context: Context): Double {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> feet
            UnitSystem.METRIC -> feet * 0.3048 // feet to meters
        }
    }
    
    /**
     * Convert display height back to feet for storage
     */
    fun displayHeightToFeet(displayHeight: Double, context: Context): Double {
        val unitSystem = getUnitSystem(context)
        return when (unitSystem) {
            UnitSystem.IMPERIAL -> displayHeight
            UnitSystem.METRIC -> displayHeight / 0.3048 // meters to feet
        }
    }
}
