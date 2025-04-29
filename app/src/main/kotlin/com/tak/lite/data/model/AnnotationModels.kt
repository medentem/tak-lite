package com.tak.lite.data.model

import com.google.android.gms.maps.model.LatLng

enum class AnnotationType {
    POINT,
    LINE,
    AREA
}

enum class AnnotationColor {
    GREEN,
    YELLOW,
    RED,
    BLACK
}

enum class AnnotationShape {
    CIRCLE,
    EXCLAMATION
}

data class Annotation(
    val id: String,
    val type: AnnotationType,
    val color: AnnotationColor,
    val shape: AnnotationShape? = null,
    val points: List<LatLng>
) 