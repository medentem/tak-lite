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
        data class Delete(val id: String) : Option()
    }

    interface OnOptionSelectedListener {
        fun onOptionSelected(option: Option)
        fun onMenuDismissed()
    }

    var center: PointF = PointF(0f, 0f)
    var options: List<Option> = emptyList()
    var listener: OnOptionSelectedListener? = null
    private var selectedIndex: Int? = null
    var menuRadius: Float = 200f
    var menuFanAngle: Double = 1.5 * Math.PI
    var menuStartAngle: Double = 5 * Math.PI / 4
    var screenSize: PointF = PointF(0f, 0f)
    private val iconRadius = 40f
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
            center.x - menuRadius - iconRadius, center.y - menuRadius - iconRadius,
            center.x + menuRadius + iconRadius, center.y + menuRadius + iconRadius,
            Math.toDegrees(menuStartAngle).toFloat(), Math.toDegrees(menuFanAngle).toFloat(), true, backgroundPaint
        )
        // Draw dividing lines and options
        val angleStep = menuFanAngle / (options.size)
        for ((i, option) in options.withIndex()) {
            val angle = menuStartAngle + (i + 0.5) * angleStep // Center of sector
            val x = center.x + menuRadius * cos(angle).toFloat()
            val y = center.y + menuRadius * sin(angle).toFloat()
            val isSelected = i == selectedIndex
            // Draw dividing lines (sector boundaries)
            if (i > 0) {
                val boundaryAngle = menuStartAngle + i * angleStep
                val bx = center.x + menuRadius * cos(boundaryAngle).toFloat()
                val by = center.y + menuRadius * sin(boundaryAngle).toFloat()
                canvas.drawLine(center.x, center.y, bx, by, paint)
            }
            when (option) {
                is Option.Shape -> drawShapeIcon(canvas, x, y, option.shape, isSelected)
                is Option.Color -> drawColorIcon(canvas, x, y, option.color, isSelected)
                is Option.Delete -> drawDeleteIcon(canvas, x, y, isSelected)
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

    private fun drawDeleteIcon(canvas: Canvas, x: Float, y: Float, highlight: Boolean) {
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.RED else Color.LTGRAY
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, iconPaint)
        val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        val size = iconRadius / 1.5f
        canvas.drawLine(x - size, y - size, x + size, y + size, xPaint)
        canvas.drawLine(x - size, y + size, x + size, y - size, xPaint)
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
        if (distance > menuRadius + iconRadius) return null
        val angle = (atan2(dy.toDouble(), dx.toDouble()) + 2 * Math.PI) % (2 * Math.PI)
        val startAngle = menuStartAngle
        val fanAngle = menuFanAngle
        val endAngle = (startAngle + fanAngle) % (2 * Math.PI)
        val inFan = if (startAngle < endAngle) {
            angle >= startAngle && angle <= endAngle
        } else {
            angle >= startAngle || angle <= endAngle
        }
        if (!inFan) return null
        val angleStep = fanAngle / (options.size)
        // Find which sector the angle falls into
        var sector = ((angle - startAngle + 2 * Math.PI) % (2 * Math.PI)) / angleStep
        val idx = sector.toInt().coerceIn(0, options.size - 1)
        return idx
    }

    fun showAt(center: PointF, options: List<Option>, listener: OnOptionSelectedListener, screenSize: PointF? = null) {
        this.center = center
        this.options = options
        this.listener = listener
        this.selectedIndex = null
        val minFanAngle = Math.PI // 180 degrees
        val maxFanAngle = 2 * Math.PI // 360 degrees
        val minRadius = 200f
        val maxRadius = 400f // You can adjust this as needed
        val minAngleBetween = Math.PI / 8 // 22.5 degrees between items minimum
        val iconSpacing = iconRadius * 2 * 1.3 // 30% extra spacing for more padding
        val n = options.size
        var requiredFanAngle = minFanAngle
        var requiredRadius = minRadius
        if (n > 1) {
            // Calculate the minimum angle needed to avoid overlap at minRadius
            val angleBetween = Math.max(minAngleBetween, 2 * Math.asin(iconSpacing / (2 * minRadius)))
            val totalAngleNeeded = angleBetween * (n - 1)
            if (totalAngleNeeded <= maxFanAngle) {
                requiredFanAngle = Math.max(minFanAngle, totalAngleNeeded)
                requiredRadius = minRadius
            } else {
                // Use full circle, increase radius to fit all items
                requiredFanAngle = maxFanAngle
                // Calculate the radius needed to fit all items in a full circle
                val neededRadius = (iconSpacing / (2 * Math.sin(Math.PI / n))).toFloat()
                requiredRadius = neededRadius.coerceAtLeast(minRadius).coerceAtMost(maxRadius)
            }
        }
        menuFanAngle = requiredFanAngle
        menuRadius = requiredRadius
        if (screenSize != null) {
            this.screenSize = screenSize
            val dx = (screenSize.x / 2) - center.x
            val dy = (screenSize.y / 2) - center.y
            val angleToCenter = atan2(dy, dx)
            menuStartAngle = angleToCenter - menuFanAngle / 2
        }
        visibility = VISIBLE
        invalidate()
    }

    fun dismiss() {
        visibility = GONE
        listener?.onMenuDismissed()
    }
} 