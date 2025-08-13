package com.tak.lite.ui.map

import com.tak.lite.model.LatLngSerializable

/**
 * Represents a distance label for a line segment
 */
data class DistanceLabel(
    val segmentIndex: Int,
    val distanceMeters: Double,
    val midpoint: LatLngSerializable
)

/**
 * Represents a distance feature for a line segment
 */
data class DistanceFeature(
    val lineId: String,
    val segmentIndex: Int,
    val distanceMeters: Double,
    val midpoint: LatLngSerializable
)
