package com.tak.lite.ui.location

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.pow

/**
 * A custom view for displaying cardinal directions with smooth, stable animations.
 * This replaces the complex LinearLayout-based approach with a simpler, more reliable implementation.
 */
class CompassCardinalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }

    private val directions = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val directionAngles = listOf(0f, 45f, 90f, 135f, 180f, 225f, 270f, 315f)
    
    // Current heading and target heading for smooth interpolation
    private var currentHeading: Float = 0f
    private var targetHeading: Float = 0f
    private var lastUpdateTime: Long = 0
    
    // Animation parameters
    private val animationDuration = 300L // milliseconds
    private val updateThreshold = 2f // degrees - only update if change is significant
    
    // Visual parameters
    private val textSize = 18f * resources.displayMetrics.density
    private val letterSpacing = 32f * resources.displayMetrics.density
    private val centerLetterScale = 1.4f
    private val sideLetterScale = 0.8f
    private val centerLetterAlpha = 1.0f
    private val sideLetterAlpha = 0.6f
    
    // Colors
    private val centerColor = Color.WHITE
    private val sideColor = Color.parseColor("#B0B0B0")
    
    init {
        paint.textSize = textSize
    }

    /**
     * Update the compass heading with smooth animation
     */
    fun updateHeading(heading: Float) {
        val normalizedHeading = (heading + 360f) % 360f
        
        // Only update if the change is significant enough
        if (abs(normalizedHeading - targetHeading) < updateThreshold) {
            return
        }
        
        targetHeading = normalizedHeading
        lastUpdateTime = System.currentTimeMillis()
        
        // Trigger animation
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - lastUpdateTime
        
        // Smooth interpolation
        if (elapsed < animationDuration) {
            val progress = elapsed.toFloat() / animationDuration
            val interpolatedProgress = easeInOutCubic(progress)
            currentHeading = lerp(currentHeading, targetHeading, interpolatedProgress)
            
            // Continue animation
            postInvalidateOnAnimation()
        } else {
            currentHeading = targetHeading
        }
        
        drawCompassLetters(canvas)
    }

    private fun drawCompassLetters(canvas: Canvas) {
        val centerX = width / 2f
        val centerY = height / 2f
        val fontMetrics = paint.fontMetrics
        val textHeight = fontMetrics.descent - fontMetrics.ascent
        val textBaseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2

        // Find the primary direction (the one closest to current heading)
        val primaryIndex = findPrimaryDirection(currentHeading)

        // Draw 5 letters centered around the primary direction
        for (i in -2..2) {
            val directionIndex = (primaryIndex + i + directions.size) % directions.size
            val direction = directions[directionIndex]

            // Calculate position
            val x = centerX + i * letterSpacing
            val y = textBaseline

            // Calculate visual properties based on distance from center
            val distanceFromCenter = abs(i)
            val isCenter = distanceFromCenter == 0

            // Set paint properties
            paint.color = if (isCenter) centerColor else sideColor
            paint.alpha = ((if (isCenter) centerLetterAlpha else sideLetterAlpha) * 255).toInt()
            paint.textSize = if (isCenter) textSize * centerLetterScale else textSize * sideLetterScale
            paint.typeface = if (isCenter) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            // Draw the letter
            canvas.drawText(direction, x, y, paint)
        }
    }

    private fun findPrimaryDirection(heading: Float): Int {
        var minDistance = Float.MAX_VALUE
        var primaryIndex = 0
        
        for (i in directions.indices) {
            val angle = directionAngles[i]
            val distance = abs(((heading - angle + 180f) % 360f) - 180f)
            
            if (distance < minDistance) {
                minDistance = distance
                primaryIndex = i
            }
        }
        
        return primaryIndex
    }

    private fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t.coerceIn(0f, 1f)
    }

    private fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            1f - (-2f * t + 2f).pow(3f) / 2f
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = (letterSpacing * 5).toInt()
        val desiredHeight = (textSize * 1.5f).toInt()
        
        val width = resolveSize(desiredWidth, widthMeasureSpec)
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        
        setMeasuredDimension(width, height)
    }
} 