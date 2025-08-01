package com.tak.lite.ui.map

import org.maplibre.android.geometry.LatLng

data class PopoverData(
    val id: String,
    val type: PopoverType,
    val position: LatLng,
    val content: String,
    val timestamp: Long,
    val autoDismissTime: Long
)

enum class PopoverType {
    PEER, POI
} 