package com.tak.lite.ui.location

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import kotlin.math.PI

class CalibrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val pathPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.parseColor("#4CAF50")
    }
    
    private val phonePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    
    private val phoneStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.parseColor("#2196F3")
    }
    
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#FF9800")
    }
    
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#4CAF50")
    }
    
    private val path = Path()
    private var animationPhase = 0f
    private var progress = 0f
    private var isAnimating = false
    private var currentStep = 0
    
    // Calibration steps
    private val steps = listOf(
        "Hold phone level",
        "Move in figure-8",
        "Rotate slowly",
        "Complete!"
    )
    
    fun startFigure8Animation() {
        isAnimating = true
        currentStep = 1
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 8000 // 8 seconds for full cycle
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                animationPhase = animator.animatedValue as Float
                invalidate()
            }
        }
        animator.start()
    }
    
    fun stopAnimation() {
        isAnimating = false
        currentStep = 3
        invalidate()
    }
    
    fun setProgress(progress: Float) {
        this.progress = progress
        // Update step based on progress
        currentStep = when {
            progress < 0.25f -> 0
            progress < 0.5f -> 1
            progress < 0.75f -> 2
            else -> 3
        }
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val centerX = width / 2f
        val centerY = height / 2f
        val radius = min(width, height) / 3f
        
        // Draw background circle
        canvas.drawCircle(centerX, centerY, radius, Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.parseColor("#333333")
        })
        
        // Draw figure-8 path
        drawFigure8Path(canvas, centerX, centerY, radius)
        
        // Draw phone icon at current position
        drawPhoneIcon(canvas, centerX, centerY, radius)
        
        // Draw progress indicator
        drawProgressIndicator(canvas, centerX, centerY, radius)
        
        // Draw step indicator
        drawStepIndicator(canvas, centerX, centerY, radius)
    }
    
    private fun drawFigure8Path(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        path.reset()
        
        // Create a clearer figure-8 pattern
        val numPoints = 100
        for (i in 0..numPoints) {
            val t = (i.toFloat() / numPoints) * 2 * PI.toFloat()
            val x = centerX + radius * 0.6f * sin(t)
            val y = centerY + radius * 0.6f * sin(t) * cos(t)
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        // Draw the path
        canvas.drawPath(path, pathPaint)
    }
    
    private fun drawPhoneIcon(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val phoneWidth = 40f
        val phoneHeight = 60f
        
        // Calculate phone position based on animation phase
        val t = animationPhase * 2 * PI.toFloat()
        val phoneX = centerX + radius * 0.6f * sin(t)
        val phoneY = centerY + radius * 0.6f * sin(t) * cos(t)
        
        // Draw phone body
        val phoneRect = RectF(
            phoneX - phoneWidth / 2,
            phoneY - phoneHeight / 2,
            phoneX + phoneWidth / 2,
            phoneY + phoneHeight / 2
        )
        
        // Rounded corners
        val cornerRadius = 8f
        canvas.drawRoundRect(phoneRect, cornerRadius, cornerRadius, phonePaint)
        canvas.drawRoundRect(phoneRect, cornerRadius, cornerRadius, phoneStrokePaint)
        
        // Draw screen
        val screenRect = RectF(
            phoneX - phoneWidth / 2 + 4,
            phoneY - phoneHeight / 2 + 8,
            phoneX + phoneWidth / 2 - 4,
            phoneY + phoneHeight / 2 - 8
        )
        canvas.drawRoundRect(screenRect, 4f, 4f, Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#E0E0E0")
        })
        
        // Draw home button
        canvas.drawCircle(phoneX, phoneY + phoneHeight / 2 - 8, 6f, Paint().apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#9E9E9E")
        })
        
        // Draw direction arrow
        val arrowLength = 30f
        val arrowAngle = when {
            animationPhase < 0.25f -> 45f
            animationPhase < 0.5f -> 135f
            animationPhase < 0.75f -> 225f
            else -> 315f
        }
        
        val arrowEndX = phoneX + arrowLength * cos(Math.toRadians(arrowAngle.toDouble())).toFloat()
        val arrowEndY = phoneY + arrowLength * sin(Math.toRadians(arrowAngle.toDouble())).toFloat()
        
        // Draw arrow
        val arrowPath = Path()
        arrowPath.moveTo(phoneX, phoneY)
        arrowPath.lineTo(arrowEndX, arrowEndY)
        
        // Arrow head
        val headLength = 12f
        val headAngle = 30f
        val angle1 = arrowAngle + headAngle
        val angle2 = arrowAngle - headAngle
        
        val head1X = arrowEndX - headLength * cos(Math.toRadians(angle1.toDouble())).toFloat()
        val head1Y = arrowEndY - headLength * sin(Math.toRadians(angle1.toDouble())).toFloat()
        val head2X = arrowEndX - headLength * cos(Math.toRadians(angle2.toDouble())).toFloat()
        val head2Y = arrowEndY - headLength * sin(Math.toRadians(angle2.toDouble())).toFloat()
        
        arrowPath.lineTo(head1X, head1Y)
        arrowPath.moveTo(arrowEndX, arrowEndY)
        arrowPath.lineTo(head2X, head2Y)
        
        canvas.drawPath(arrowPath, arrowPaint)
    }
    
    private fun drawProgressIndicator(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        if (progress > 0) {
            val progressRadius = radius + 30f
            val sweepAngle = progress * 360f
            
            canvas.drawArc(
                centerX - progressRadius,
                centerY - progressRadius,
                centerX + progressRadius,
                centerY + progressRadius,
                -90f,
                sweepAngle,
                false,
                progressPaint
            )
        }
    }
    
    private fun drawStepIndicator(canvas: Canvas, centerX: Float, centerY: Float, radius: Float) {
        val stepText = steps.getOrElse(currentStep) { "" }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 16f
            textAlign = Paint.Align.CENTER
        }
        
        canvas.drawText(stepText, centerX, centerY + radius + 40, textPaint)
        
        // Draw step dots
        val dotRadius = 6f
        val dotSpacing = 20f
        val totalWidth = (steps.size - 1) * dotSpacing
        val startX = centerX - totalWidth / 2
        
        for (i in steps.indices) {
            val dotX = startX + i * dotSpacing
            val dotY = centerY + radius + 60
            
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = if (i <= currentStep) Color.parseColor("#4CAF50") else Color.parseColor("#666666")
            }
            
            canvas.drawCircle(dotX, dotY, dotRadius, dotPaint)
        }
    }
} 