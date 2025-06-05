package com.tak.lite.data.model

import android.graphics.RectF
import com.tak.lite.model.MapAnnotation
import org.maplibre.android.geometry.LatLng

data class AnnotationCluster(
    val center: LatLng,
    val annotations: List<MapAnnotation>,
    val bounds: RectF
) 