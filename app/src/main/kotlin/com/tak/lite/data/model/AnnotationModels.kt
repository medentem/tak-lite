package com.tak.lite.data.model

import com.google.android.gms.maps.model.LatLng
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape

// Extension functions to convert between MapAnnotation and Google Maps LatLng
fun MapAnnotation.PointOfInterest.toGoogleLatLng(): LatLng {
    return position.toGoogleLatLng()
}

fun MapAnnotation.Line.toGoogleLatLngs(): List<LatLng> {
    return points.map { it.toGoogleLatLng() }
}

fun MapAnnotation.Area.toGoogleLatLng(): LatLng {
    return center.toGoogleLatLng()
}

// Extension function to get annotation type
val MapAnnotation.type: AnnotationType
    get() = when (this) {
        is MapAnnotation.PointOfInterest -> AnnotationType.POINT
        is MapAnnotation.Line -> AnnotationType.LINE
        is MapAnnotation.Area -> AnnotationType.AREA
    }

enum class AnnotationType {
    POINT,
    LINE,
    AREA
} 