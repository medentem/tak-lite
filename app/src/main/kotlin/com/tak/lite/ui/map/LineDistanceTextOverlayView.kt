package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import org.maplibre.android.maps.Projection

/**
 * Custom overlay view for rendering line distance labels
 * Uses hybrid approach to avoid MapLibre symbol layer conflicts
 */
class LineDistanceTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), LineDistanceManager.DistanceTextOverlayCallback {

    companion object {
        private const val TAG = "LineDistanceTextOverlayView"
    }

    private var projection: Projection? = null
    private var distanceFeatures: List<DistanceFeature> = emptyList()
    
    // Paint for text rendering
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0) // Semi-transparent black
        style = Paint.Style.FILL
    }

    /**
     * Set the map projection for coordinate conversion
     */
    fun setProjection(projection: Projection?) {
        this.projection = projection
        invalidate()
    }

    /**
     * Update distance features from the distance manager
     */
    override fun updateDistanceTexts(distanceFeatures: List<DistanceFeature>) {
        this.distanceFeatures = distanceFeatures
        invalidate()
        Log.d(TAG, "Updated distance texts: ${distanceFeatures.size} labels")
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (projection == null || distanceFeatures.isEmpty()) {
            return
        }

        for (distanceFeature in distanceFeatures) {
            try {
                // Convert lat/lng to screen coordinates
                val latLng = org.maplibre.android.geometry.LatLng(
                    distanceFeature.midpoint.lt,
                    distanceFeature.midpoint.lng
                )
                val screenPoint = projection!!.toScreenLocation(latLng)
                
                // Draw distance text at the midpoint of the line segment
                val distanceText = "%.2f mi".format(distanceFeature.distanceMiles)
                drawDistanceText(canvas, PointF(screenPoint.x, screenPoint.y), distanceText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing distance text for line ${distanceFeature.lineId} segment ${distanceFeature.segmentIndex}", e)
            }
        }
    }

    /**
     * Draw distance text with background
     */
    private fun drawDistanceText(canvas: Canvas, center: PointF, text: String) {
        // Measure text bounds
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        
        // Calculate background rectangle
        val paddingH = 12f
        val paddingV = 6f
        val rectWidth = textBounds.width() + paddingH * 2
        val rectHeight = textBounds.height() + paddingV * 2
        val rectLeft = center.x - rectWidth / 2
        val rectTop = center.y - rectHeight / 2
        val rectRight = center.x + rectWidth / 2
        val rectBottom = rectTop + rectHeight
        
        // Draw background
        val backgroundRect = RectF(rectLeft, rectTop, rectRight, rectBottom)
        canvas.drawRoundRect(backgroundRect, rectHeight / 2, rectHeight / 2, backgroundPaint)
        
        // Draw text
        val textY = rectTop + rectHeight / 2 - textBounds.exactCenterY()
        canvas.drawText(text, center.x, textY, textPaint)
    }
}
