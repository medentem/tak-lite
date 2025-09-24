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

/**
 * Server annotation response from the API
 */
data class ServerAnnotation(
    val id: String,
    val user_id: String,
    val team_id: String?,
    val type: String,
    val data: Map<String, Any>,
    val created_at: String,
    val updated_at: String
) 