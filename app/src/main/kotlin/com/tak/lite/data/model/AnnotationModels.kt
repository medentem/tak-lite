package com.tak.lite.data.model

import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import org.maplibre.android.geometry.LatLng

// Extension functions to convert between MapAnnotation and MapLibre LatLng
fun MapAnnotation.PointOfInterest.toMapLibreLatLng(): LatLng {
    return LatLng(position.latitude, position.longitude)
}

fun MapAnnotation.Line.toMapLibreLatLngs(): List<LatLng> {
    return points.map { LatLng(it.latitude, it.longitude) }
}

fun MapAnnotation.Area.toMapLibreLatLng(): LatLng {
    return LatLng(center.latitude, center.longitude)
}

// Extension function to get annotation type
val MapAnnotation.type: AnnotationType?
    get() = when (this) {
        is MapAnnotation.PointOfInterest -> AnnotationType.POINT
        is MapAnnotation.Line -> AnnotationType.LINE
        is MapAnnotation.Area -> AnnotationType.AREA
        is MapAnnotation.Deletion -> null
    }

enum class AnnotationType {
    POINT,
    LINE,
    AREA
} 