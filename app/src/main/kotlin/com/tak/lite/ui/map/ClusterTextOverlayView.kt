package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.maplibre.android.maps.Projection

/**
 * Custom overlay view for rendering cluster count labels
 * Handles both peer clusters and POI clusters
 */
class ClusterTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "ClusterTextOverlayView"
    }

    private var projection: Projection? = null
    private var peerClusters: List<ClusterFeature> = emptyList()
    private var poiClusters: List<ClusterFeature> = emptyList()
    
    // Paint for peer cluster text rendering
    private val peerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    
    // Paint for POI cluster text rendering
    private val poiTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 32f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    /**
     * Represents a cluster feature with position and count
     */
    data class ClusterFeature(
        val clusterId: String,
        val position: org.maplibre.geojson.Point,
        val pointCount: Int,
        val clusterType: ClusterType
    )

    enum class ClusterType {
        PEER, POI
    }

    /**
     * Set the map projection for coordinate conversion
     */
    fun setProjection(projection: Projection?) {
        this.projection = projection
        invalidate()
    }

    /**
     * Update peer cluster features
     */
    fun updatePeerClusters(clusters: List<ClusterFeature>) {
        this.peerClusters = clusters
        invalidate()
    }

    /**
     * Update POI cluster features
     */
    fun updatePoiClusters(clusters: List<ClusterFeature>) {
        this.poiClusters = clusters
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (projection == null) {
            return
        }

        // Draw peer cluster counts
        for (cluster in peerClusters) {
            try {
                drawClusterCount(canvas, cluster, peerTextPaint)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing peer cluster text for ${cluster.clusterId}", e)
            }
        }

        // Draw POI cluster counts
        for (cluster in poiClusters) {
            try {
                drawClusterCount(canvas, cluster, poiTextPaint)
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing POI cluster text for ${cluster.clusterId}", e)
            }
        }
    }

    /**
     * Draw cluster count text
     */
    private fun drawClusterCount(canvas: Canvas, cluster: ClusterFeature, textPaint: Paint) {
        // Convert lat/lng to screen coordinates
        val latLng = org.maplibre.android.geometry.LatLng(
            cluster.position.coordinates()[1],
            cluster.position.coordinates()[0]
        )
        val screenPoint = projection!!.toScreenLocation(latLng)
        
        // Draw the count text
        val countText = cluster.pointCount.toString()
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(countText, 0, countText.length, textBounds)
        
        // Position text at center of cluster
        val textX = screenPoint.x
        val textY = screenPoint.y + textBounds.height() / 3 // Adjust for baseline
        
        canvas.drawText(countText, textX, textY, textPaint)
        
        Log.d(TAG, "Drew cluster count: ${cluster.clusterType} cluster ${cluster.clusterId} with ${cluster.pointCount} points at ($textX, $textY)")
    }

    /**
     * Clear all clusters
     */
    fun clearClusters() {
        peerClusters = emptyList()
        poiClusters = emptyList()
        invalidate()
    }
} 