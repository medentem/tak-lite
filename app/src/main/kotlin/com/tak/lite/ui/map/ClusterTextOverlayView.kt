package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.maplibre.android.maps.Projection

/**
 * Optimized overlay view for rendering cluster count labels
 * Handles both peer clusters and POI clusters with performance optimizations
 */
class ClusterTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "ClusterTextOverlayView"
        private const val VIEWPORT_PADDING_PX = 100f // Extra padding for smooth scrolling
        private const val MIN_UPDATE_INTERVAL_MS = 50L // Reduced for better responsiveness
        private const val MAX_VISIBLE_CLUSTERS = 50 // Limit visible clusters for performance
        
        // Temporary flag to disable cluster text labels
        private const val CLUSTER_TEXT_ENABLED = false
    }

    private var projection: Projection? = null
    private var peerClusters: List<ClusterFeature> = emptyList()
    private var poiClusters: List<ClusterFeature> = emptyList()
    
    // Performance optimization: Cache visible clusters
    private var visiblePeerClusters: List<CachedCluster> = emptyList()
    private var visiblePoiClusters: List<CachedCluster> = emptyList()
    
    // Performance optimization: Throttle updates
    private var lastUpdateTime: Long = 0
    private var lastProjection: Projection? = null
    
    // Performance optimization: Viewport bounds
    private var viewportBounds: RectF = RectF()
    private var screenBounds: RectF = RectF()
    
    // Performance optimization: Hardware acceleration
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }
    
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

    /**
     * Cached cluster data for performance optimization
     */
    private data class CachedCluster(
        val cluster: ClusterFeature,
        val screenX: Float,
        val screenY: Float,
        val textBounds: Rect,
        val countText: String
    )

    enum class ClusterType {
        PEER, POI
    }

    /**
     * Set the map projection for coordinate conversion
     * Optimized to only update when projection actually changes
     */
    fun setProjection(projection: Projection?) {
        if (this.projection != projection) {
            this.projection = projection
            // Clear cached data when projection changes
            clearCachedClusters()
            invalidate()
        }
    }

    /**
     * Update peer cluster features with throttling
     */
    fun updatePeerClusters(clusters: List<ClusterFeature>) {
        if (clusters != peerClusters) {
            peerClusters = clusters
            updateVisibleClusters()
        }
    }

    /**
     * Update POI cluster features with throttling
     */
    fun updatePoiClusters(clusters: List<ClusterFeature>) {
        if (clusters != poiClusters) {
            poiClusters = clusters
            updateVisibleClusters()
        }
    }

    /**
     * Update visible clusters with viewport culling
     */
    private fun updateVisibleClusters() {
        val now = System.currentTimeMillis()
        
        // Always update immediately if projection changed or during camera movement
        val shouldUpdateImmediately = projection != lastProjection || 
                                    (now - lastUpdateTime) >= MIN_UPDATE_INTERVAL_MS
        
        if (!shouldUpdateImmediately) {
            return // Throttle updates only when not moving
        }
        
        lastUpdateTime = now
        lastProjection = projection

        if (projection == null) return

        // Update viewport bounds
        updateViewportBounds()

        // Filter and cache visible clusters
        visiblePeerClusters = getVisibleClusters(peerClusters, ClusterType.PEER)
        visiblePoiClusters = getVisibleClusters(poiClusters, ClusterType.POI)

        invalidate()
    }

    /**
     * Update viewport bounds for culling
     */
    private fun updateViewportBounds() {
        val width = width.toFloat()
        val height = height.toFloat()
        
        screenBounds.set(0f, 0f, width, height)
        viewportBounds.set(
            -VIEWPORT_PADDING_PX,
            -VIEWPORT_PADDING_PX,
            width + VIEWPORT_PADDING_PX,
            height + VIEWPORT_PADDING_PX
        )
    }

    /**
     * Get visible clusters with viewport culling
     */
    private fun getVisibleClusters(
        clusters: List<ClusterFeature>,
        clusterType: ClusterType
    ): List<CachedCluster> {
        val visibleClusters = mutableListOf<CachedCluster>()
        
        for (cluster in clusters.take(MAX_VISIBLE_CLUSTERS)) {
            try {
                val cachedCluster = createCachedCluster(cluster, clusterType)
                if (cachedCluster != null && isInViewport(cachedCluster.screenX, cachedCluster.screenY)) {
                    visibleClusters.add(cachedCluster)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing cluster ${cluster.clusterId}", e)
            }
        }
        
        return visibleClusters
    }

    /**
     * Create cached cluster data
     */
    private fun createCachedCluster(
        cluster: ClusterFeature,
        clusterType: ClusterType
    ): CachedCluster? {
        val latLng = org.maplibre.android.geometry.LatLng(
            cluster.position.coordinates()[1],
            cluster.position.coordinates()[0]
        )
        
        val screenPoint = projection!!.toScreenLocation(latLng)
        val countText = cluster.pointCount.toString()
        
        // Cache text bounds
        val textBounds = Rect()
        val paint = if (clusterType == ClusterType.PEER) peerTextPaint else poiTextPaint
        paint.getTextBounds(countText, 0, countText.length, textBounds)
        
        return CachedCluster(
            cluster = cluster,
            screenX = screenPoint.x,
            screenY = screenPoint.y + textBounds.height() / 3,
            textBounds = textBounds,
            countText = countText
        )
    }

    /**
     * Check if point is in viewport
     */
    private fun isInViewport(x: Float, y: Float): Boolean {
        return viewportBounds.contains(x, y)
    }

    /**
     * Clear cached cluster data
     */
    private fun clearCachedClusters() {
        visiblePeerClusters = emptyList()
        visiblePoiClusters = emptyList()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Skip rendering if cluster text is disabled
        if (!CLUSTER_TEXT_ENABLED) {
            return
        }
        
        if (projection == null) {
            return
        }

        // Draw cached peer cluster counts
        for (cachedCluster in visiblePeerClusters) {
            try {
                canvas.drawText(
                    cachedCluster.countText,
                    cachedCluster.screenX,
                    cachedCluster.screenY,
                    peerTextPaint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing peer cluster text for ${cachedCluster.cluster.clusterId}", e)
            }
        }

        // Draw cached POI cluster counts
        for (cachedCluster in visiblePoiClusters) {
            try {
                canvas.drawText(
                    cachedCluster.countText,
                    cachedCluster.screenX,
                    cachedCluster.screenY,
                    poiTextPaint
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing POI cluster text for ${cachedCluster.cluster.clusterId}", e)
            }
        }
    }

    /**
     * Clear all clusters
     */
    fun clearClusters() {
        peerClusters = emptyList()
        poiClusters = emptyList()
        clearCachedClusters()
        invalidate()
    }

    /**
     * Force update of visible clusters (for external triggers)
     */
    fun forceUpdate() {
        lastUpdateTime = 0 // Reset throttle
        updateVisibleClusters()
    }
} 