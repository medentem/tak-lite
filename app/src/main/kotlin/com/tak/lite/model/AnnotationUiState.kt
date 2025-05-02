package com.tak.lite.model

data class AnnotationUiState(
    val isDrawingEnabled: Boolean = false,
    val selectedAnnotationType: AnnotationType = AnnotationType.POINT,
    val selectedColor: AnnotationColor = AnnotationColor.RED,
    val selectedPointShape: PointShape = PointShape.CIRCLE,
    val annotations: List<MapAnnotation> = emptyList()
) 