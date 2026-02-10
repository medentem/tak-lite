package com.tak.lite.ui.map

import org.maplibre.android.geometry.LatLng

data class PopoverData(
    val id: String,
    val type: PopoverType,
    val position: LatLng,
    val content: String,
    val timestamp: Long,
    val autoDismissTime: Long,
    val status: com.tak.lite.model.AnnotationStatus? = null,
    /** When non-empty, show a "View source" link that opens the first URL (e.g. threat citation). */
    val citationUrls: List<String> = emptyList()
)

enum class PopoverType {
    PEER, POI, LINE, POLYGON, AREA
} 