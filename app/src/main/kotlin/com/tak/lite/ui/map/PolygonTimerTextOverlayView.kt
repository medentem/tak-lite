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
import com.tak.lite.ui.map.PolygonTimerManager.TimerFeature
import org.maplibre.android.maps.Projection

/**
 * Custom overlay view for rendering polygon timer countdown text
 */
class PolygonTimerTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), PolygonTimerManager.TimerTextOverlayCallback {

    companion object {
        private const val TAG = "PolygonTimerTextOverlayView"
    }

    private var projection: Projection? = null
    private var timerFeatures: List<TimerFeature> = emptyList()
    
    // Paint for text rendering
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
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
     * Update timer features from the timer manager
     */
    override fun updateTimerTexts(timerFeatures: List<TimerFeature>) {
        this.timerFeatures = timerFeatures
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (projection == null || timerFeatures.isEmpty()) {
            return
        }

        for (timerFeature in timerFeatures) {
            try {
                // Convert lat/lng to screen coordinates
                val latLng = org.maplibre.android.geometry.LatLng(
                    timerFeature.position.coordinates()[1],
                    timerFeature.position.coordinates()[0]
                )
                val screenPoint = projection!!.toScreenLocation(latLng)
                
                // Draw countdown text at the polygon center
                val countdownText = formatCountdownText(timerFeature.secondsRemaining)
                drawCountdownText(canvas, PointF(screenPoint.x, screenPoint.y), countdownText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing timer text for ${timerFeature.polygonId}", e)
            }
        }
    }

    /**
     * Draw countdown text with background
     */
    private fun drawCountdownText(canvas: Canvas, center: PointF, text: String) {
        // Measure text bounds
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        
        // Calculate background rectangle
        val paddingH = 16f
        val paddingV = 8f
        val rectWidth = textBounds.width() + paddingH * 2
        val rectHeight = textBounds.height() + paddingV * 2
        val rectLeft = center.x - rectWidth / 2
        val rectTop = center.y - rectHeight / 2 // Center on the polygon center
        
        // Draw background
        val backgroundRect = RectF(rectLeft, rectTop, rectLeft + rectWidth, rectTop + rectHeight)
        canvas.drawRoundRect(backgroundRect, 8f, 8f, backgroundPaint)
        
        // Draw text
        val textX = center.x
        val textY = center.y + textBounds.height() / 2 - textBounds.bottom
        canvas.drawText(text, textX, textY, textPaint)
    }

    /**
     * Format countdown text for display
     */
    private fun formatCountdownText(secondsRemaining: Long): String {
        return when {
            secondsRemaining >= 60 -> {
                val minutes = secondsRemaining / 60
                val seconds = secondsRemaining % 60
                "${minutes}m ${seconds}s"
            }
            secondsRemaining > 0 -> "${secondsRemaining}s"
            else -> "EXPIRED"
        }
    }
} 