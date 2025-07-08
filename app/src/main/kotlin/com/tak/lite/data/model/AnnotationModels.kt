package com.tak.lite.data.model

import android.graphics.RectF
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PeerLocationEntry
import org.maplibre.android.geometry.LatLng

data class AnnotationCluster(
    val center: LatLng,
    val annotations: List<MapAnnotation>,
    val bounds: RectF
)

data class PeerCluster(
    val center: LatLng,
    val peers: List<Pair<String, PeerLocationEntry>>,
    val bounds: RectF
) 