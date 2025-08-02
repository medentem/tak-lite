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
import com.tak.lite.ui.map.LineTimerManager.TimerFeature
import org.maplibre.android.maps.Projection

/**
 * Custom overlay view for rendering line timer countdown text
 */
class LineTimerTextOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr), LineTimerManager.TimerTextOverlayCallback {

    companion object {
        private const val TAG = "LineTimerTextOverlayView"
    }

    private var projection: Projection? = null
    private var timerFeatures: List<TimerFeature> = emptyList()
    
    // Paint for text rendering
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f // Slightly smaller than POI timers
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
                
                // Draw countdown text below the line segment
                val countdownText = formatCountdownText(timerFeature.secondsRemaining)
                drawCountdownText(canvas, PointF(screenPoint.x, screenPoint.y), countdownText)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error drawing line timer text for ${timerFeature.lineId} segment ${timerFeature.segmentIndex}", e)
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
        val paddingH = 12f // Slightly smaller padding than POI timers
        val paddingV = 6f
        val rectWidth = textBounds.width() + paddingH * 2
        val rectHeight = textBounds.height() + paddingV * 2
        val rectLeft = center.x - rectWidth / 2
        val rectTop = center.y + 40f // Position below the line segment (closer than POI timers)
        val rectRight = center.x + rectWidth / 2
        val rectBottom = rectTop + rectHeight
        
        // Draw background
        val backgroundRect = RectF(rectLeft, rectTop, rectRight, rectBottom)
        canvas.drawRoundRect(backgroundRect, rectHeight / 2, rectHeight / 2, backgroundPaint)
        
        // Draw text
        val textY = rectTop + rectHeight / 2 - textBounds.exactCenterY()
        canvas.drawText(text, center.x, textY, textPaint)
    }

    /**
     * Format countdown text
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