package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.tak.lite.model.PointShape
import com.tak.lite.model.AnnotationColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

class FanMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    sealed class Option {
        data class Shape(val shape: PointShape) : Option()
        data class Color(val color: AnnotationColor) : Option()
    }

    interface OnOptionSelectedListener {
        fun onOptionSelected(option: Option)
        fun onMenuDismissed()
    }

    var center: PointF = PointF(0f, 0f)
    var options: List<Option> = emptyList()
    var listener: OnOptionSelectedListener? = null
    private var selectedIndex: Int? = null
    private val radius = 120f
    private val iconRadius = 40f
    private val fanAngle = Math.PI // 180 degrees
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (options.isEmpty()) return
        // Draw fan background
        canvas.drawArc(
            center.x - radius - iconRadius, center.y - radius - iconRadius,
            center.x + radius + iconRadius, center.y + radius + iconRadius,
            180f, 180f, true, backgroundPaint
        )
        // Draw dividing lines and options
        val angleStep = fanAngle / (options.size - 1).coerceAtLeast(1)
        for ((i, option) in options.withIndex()) {
            val angle = Math.PI + i * angleStep
            val x = center.x + radius * cos(angle).toFloat()
            val y = center.y + radius * sin(angle).toFloat()
            val isSelected = i == selectedIndex
            // Draw dividing lines
            if (i > 0) {
                canvas.drawLine(center.x, center.y, x, y, paint)
            }
            // Draw option icon
            when (option) {
                is Option.Shape -> drawShapeIcon(canvas, x, y, option.shape, isSelected)
                is Option.Color -> drawColorIcon(canvas, x, y, option.color, isSelected)
            }
        }
    }

    private fun drawShapeIcon(canvas: Canvas, x: Float, y: Float, shape: PointShape, highlight: Boolean) {
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, iconPaint)
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        when (shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(x, y, iconRadius / 2, shapePaint)
            }
            PointShape.EXCLAMATION -> {
                canvas.drawLine(x, y - 10, x, y + 10, shapePaint)
                canvas.drawCircle(x, y + 15, 3f, shapePaint)
            }
            PointShape.SQUARE -> {
                val half = iconRadius / 2
                canvas.drawRect(x - half, y - half, x + half, y + half, shapePaint)
            }
            PointShape.TRIANGLE -> {
                val half = iconRadius / 2
                val height = (half * Math.sqrt(3.0)).toFloat()
                val path = android.graphics.Path()
                path.moveTo(x, y - height / 2) // Top
                path.lineTo(x - half, y + height / 2) // Bottom left
                path.lineTo(x + half, y + height / 2) // Bottom right
                path.close()
                canvas.drawPath(path, shapePaint)
            }
        }
    }

    private fun drawColorIcon(canvas: Canvas, x: Float, y: Float, color: AnnotationColor, highlight: Boolean) {
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = when (color) {
                AnnotationColor.GREEN -> Color.parseColor("#4CAF50")
                AnnotationColor.YELLOW -> Color.parseColor("#FFEB3B")
                AnnotationColor.RED -> Color.parseColor("#F44336")
                AnnotationColor.BLACK -> Color.BLACK
            }
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = if (highlight) Color.YELLOW else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(x, y, iconRadius, fillPaint)
        canvas.drawCircle(x, y, iconRadius, borderPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_DOWN -> {
                val idx = getOptionIndexAt(event.x, event.y)
                if (idx != selectedIndex) {
                    selectedIndex = idx
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                selectedIndex?.let {
                    listener?.onOptionSelected(options[it])
                } ?: run {
                    listener?.onMenuDismissed()
                }
                selectedIndex = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                listener?.onMenuDismissed()
                selectedIndex = null
                return true
            }
        }
        return true
    }

    private fun getOptionIndexAt(x: Float, y: Float): Int? {
        val dx = x - center.x
        val dy = y - center.y
        val distance = hypot(dx.toDouble(), dy.toDouble())
        if (distance > radius + iconRadius) return null
        val angle = (atan2(dy.toDouble(), dx.toDouble()) + 2 * Math.PI) % (2 * Math.PI)
        if (angle < Math.PI) return null // Only in the fan (bottom half)
        val angleInFan = angle - Math.PI
        val angleStep = fanAngle / (options.size - 1).coerceAtLeast(1)

        // Check if touch is close to any option's icon center
        for ((i, _) in options.withIndex()) {
            val optionAngle = Math.PI + i * angleStep
            val optionX = center.x + radius * cos(optionAngle).toFloat()
            val optionY = center.y + radius * sin(optionAngle).toFloat()
            val optionDist = hypot((x - optionX).toDouble(), (y - optionY).toDouble())
            if (optionDist < iconRadius * 1.5) { // 1.5x icon radius for easier touch
                return i
            }
        }
        return null
    }

    fun showAt(center: PointF, options: List<Option>, listener: OnOptionSelectedListener) {
        this.center = center
        this.options = options
        this.listener = listener
        this.selectedIndex = null
        visibility = VISIBLE
        invalidate()
    }

    fun dismiss() {
        visibility = GONE
        listener?.onMenuDismissed()
    }
} 