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
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import android.util.Log

class FanMenuView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    sealed class Option {
        data class Shape(val shape: PointShape) : Option()
        data class Color(val color: AnnotationColor) : Option()
        data class Delete(val id: String) : Option()
        data class LineStyle(val style: com.tak.lite.model.LineStyle) : Option()
        data class Timer(val id: String) : Option()
    }

    interface OnOptionSelectedListener {
        fun onOptionSelected(option: Option): Boolean
        fun onMenuDismissed()
    }

    private var center: PointF = PointF(0f, 0f)
    private var options: List<Option> = emptyList()
    private var listener: OnOptionSelectedListener? = null
    private var selectedIndex: Int? = null
    private var menuRadius: Float = 200f
    private var menuFanAngle: Double = 1.5 * Math.PI
    private var menuStartAngle: Double = 5 * Math.PI / 4
    private var screenSize: PointF = PointF(0f, 0f)
    private val iconRadius = 60f
    private val centerHoleRadius = 135f
    private var isTransitioning = false
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(128, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }
    // Two-ring fan menu configuration
    private val maxItemsInnerRing = 6
    private val ringSpacing = iconRadius * 2.5f
    private var numInner = 0
    private var numOuter = 0

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (options.isEmpty()) return
        // Draw fan background for both rings
        for (ring in 0 until getRingCount()) {
            val radius = menuRadius + ring * ringSpacing
            canvas.drawArc(
                center.x - radius - iconRadius, center.y - radius - iconRadius,
                center.x + radius + iconRadius, center.y + radius + iconRadius,
                Math.toDegrees(menuStartAngle).toFloat(), Math.toDegrees(menuFanAngle).toFloat(), true, backgroundPaint
            )
        }
        // Draw empty center (hole)
        canvas.drawCircle(center.x, center.y, centerHoleRadius, clearPaint)
        // Draw selected sector background if any
        selectedIndex?.let { selIdx ->
            val (ring, idxInRing, itemsInRing) = getRingAndIndex(selIdx)
            val angleStep = menuFanAngle / itemsInRing
            val sectorStart = menuStartAngle + idxInRing * angleStep
            val sectorSweep = angleStep
            val outerRadius = menuRadius + ring * ringSpacing
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255) // Light, semi-transparent
                style = Paint.Style.FILL
            }
            if (ring == 0) {
                // Inner ring: highlight full sector
                canvas.drawArc(
                    center.x - outerRadius - iconRadius, center.y - outerRadius - iconRadius,
                    center.x + outerRadius + iconRadius, center.y + outerRadius + iconRadius,
                    Math.toDegrees(sectorStart).toFloat(), Math.toDegrees(sectorSweep).toFloat(), true, highlightPaint
                )
            } else {
                // Outer ring: highlight only the donut sector
                val innerRadius = menuRadius + (ring - 1) * ringSpacing
                val path = android.graphics.Path()
                val startAngleDeg = Math.toDegrees(sectorStart).toFloat()
                val sweepAngleDeg = Math.toDegrees(sectorSweep).toFloat()
                // Outer arc
                path.arcTo(
                    center.x - outerRadius - iconRadius, center.y - outerRadius - iconRadius,
                    center.x + outerRadius + iconRadius, center.y + outerRadius + iconRadius,
                    startAngleDeg, sweepAngleDeg, false
                )
                // Inner arc (reverse)
                path.arcTo(
                    center.x - innerRadius - iconRadius, center.y - innerRadius - iconRadius,
                    center.x + innerRadius + iconRadius, center.y + innerRadius + iconRadius,
                    startAngleDeg + sweepAngleDeg, -sweepAngleDeg, false
                )
                path.close()
                canvas.drawPath(path, highlightPaint)
            }
        }
        // Draw dividing lines and options for both rings
        for (i in options.indices) {
            val (ring, idxInRing, itemsInRing) = getRingAndIndex(i)
            val angleStep = menuFanAngle / itemsInRing
            val angle = menuStartAngle + (idxInRing + 0.5) * angleStep // Center of sector
            val radius = menuRadius + ring * ringSpacing
            val x = center.x + (radius + iconRadius) * cos(angle).toFloat()
            val y = center.y + (radius + iconRadius) * sin(angle).toFloat()
            val isSelected = i == selectedIndex
            // Draw dividing lines (sector boundaries)
            if (idxInRing > 0) {
                val boundaryAngle = menuStartAngle + idxInRing * angleStep
                val bx = center.x + radius * cos(boundaryAngle).toFloat()
                val by = center.y + radius * sin(boundaryAngle).toFloat()
                canvas.drawLine(center.x, center.y, bx, by, paint)
            }
            when (val option = options[i]) {
                is Option.Shape -> drawShapeIcon(canvas, x, y, option.shape, isSelected)
                is Option.Color -> drawColorIcon(canvas, x, y, option.color, isSelected)
                is Option.Delete -> drawDeleteIcon(canvas, x, y, isSelected)
                is Option.LineStyle -> drawLineStyleIcon(canvas, x, y, option.style, isSelected)
                is Option.Timer -> drawTimerIcon(canvas, x, y, isSelected)
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
                // Draw filled black triangle
                val half = iconRadius / 2f
                val height = (half * sqrt(3.0)).toFloat()
                val path = android.graphics.Path()
                path.moveTo(x, y - height / 2) // Top
                path.lineTo(x - half, y + height / 2) // Bottom left
                path.lineTo(x + half, y + height / 2) // Bottom right
                path.close()
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
                // Draw thinner white exclamation mark inside triangle
                val exMarkWidth = iconRadius * 0.10f
                val exMarkTop = y - height / 6
                val exMarkBottom = y + height / 6
                val exMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = exMarkWidth
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(x, exMarkTop, x, exMarkBottom, exMarkPaint)
                val dotRadius = exMarkWidth * 0.6f
                val dotCenterY = exMarkBottom + dotRadius * 2.0f
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(x, dotCenterY, dotRadius, dotPaint)
            }
            PointShape.SQUARE -> {
                val half = iconRadius / 2
                canvas.drawRect(x - half, y - half, x + half, y + half, shapePaint)
            }
            PointShape.TRIANGLE -> {
                val half = iconRadius / 2
                val height = (half * sqrt(3.0)).toFloat()
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
                AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
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
        // Draw background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.RED else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        // Draw circle border for contrast
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        // Trash can outline paint
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#23234B") // dark blue/black
            style = Paint.Style.STROKE
            strokeWidth = iconRadius * 0.18f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Dimensions
        val canWidth = iconRadius * 1.1f
        val canHeight = iconRadius * 1.25f
        val canTop = y - canHeight * 0.35f
        val canBottom = y + canHeight * 0.45f
        val canLeft = x - canWidth / 2.1f
        val canRight = x + canWidth / 2.1f
        // Draw can body (trapezoid)
        val bodyPath = android.graphics.Path().apply {
            moveTo(canLeft + outlinePaint.strokeWidth/2, canTop + iconRadius * 0.18f)
            lineTo(canRight - outlinePaint.strokeWidth/2, canTop + iconRadius * 0.18f)
            lineTo(canRight - iconRadius * 0.10f, canBottom - outlinePaint.strokeWidth/2)
            lineTo(canLeft + iconRadius * 0.10f, canBottom - outlinePaint.strokeWidth/2)
            close()
        }
        canvas.drawPath(bodyPath, outlinePaint)
        // Draw lid (rectangle with rounded corners)
        val lidHeight = iconRadius * 0.22f
        val lidRect = android.graphics.RectF(
            canLeft + iconRadius * 0.05f,
            canTop,
            canRight - iconRadius * 0.05f,
            canTop + lidHeight
        )
        canvas.drawRoundRect(lidRect, lidHeight/2, lidHeight/2, outlinePaint)
        // Draw handle (semicircle)
        val handleRadius = iconRadius * 0.28f
        val handleCenterY = canTop + lidHeight/2
        val handleRect = android.graphics.RectF(
            x - handleRadius,
            handleCenterY - handleRadius,
            x + handleRadius,
            handleCenterY + handleRadius
        )
        canvas.drawArc(handleRect, 180f, 180f, false, outlinePaint)
        // Draw ribs (3 vertical lines)
        val ribPaint = Paint(outlinePaint)
        ribPaint.strokeWidth = iconRadius * 0.10f
        val ribTop = canTop + lidHeight + iconRadius * 0.10f
        val ribBottom = canBottom - iconRadius * 0.10f
        val ribXs = listOf(
            x - canWidth * 0.20f,
            x,
            x + canWidth * 0.20f
        )
        for (ribX in ribXs) {
            canvas.drawLine(ribX, ribTop, ribX, ribBottom, ribPaint)
        }
    }

    private fun drawLineStyleIcon(canvas: Canvas, x: Float, y: Float, style: com.tak.lite.model.LineStyle, highlight: Boolean) {
        // Draw background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            this.style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        // Draw border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        // Draw the line style
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val yLine = y
        val xStart = x - iconRadius * 0.7f
        val xEnd = x + iconRadius * 0.7f
        if (style == com.tak.lite.model.LineStyle.DASHED) {
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(20f, 15f), 0f)
        }
        canvas.drawLine(xStart, yLine, xEnd, yLine, paint)
        // Draw label
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 28f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            if (style == com.tak.lite.model.LineStyle.SOLID) "Solid" else "Dashed",
            x,
            y + iconRadius * 0.8f,
            textPaint
        )
    }

    private fun drawTimerIcon(canvas: Canvas, x: Float, y: Float, highlight: Boolean) {
        // Draw background circle
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        
        // Draw border
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        
        // Draw timer circle
        val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }
        val timerRadius = iconRadius * 0.6f
        canvas.drawCircle(x, y, timerRadius, timerPaint)
        
        // Draw timer hands
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 6f
            strokeCap = Paint.Cap.ROUND
        }
        
        // Hour hand
        val hourHandLength = timerRadius * 0.5f
        val hourAngle = -Math.PI / 4 // 45 degrees
        canvas.drawLine(
            x,
            y,
            x + (hourHandLength * cos(hourAngle)).toFloat(),
            y + (hourHandLength * sin(hourAngle)).toFloat(),
            handPaint
        )
        
        // Minute hand
        val minuteHandLength = timerRadius * 0.8f
        val minuteAngle = Math.PI / 2 // 90 degrees
        canvas.drawLine(
            x,
            y,
            x + (minuteHandLength * cos(minuteAngle)).toFloat(),
            y + (minuteHandLength * sin(minuteAngle)).toFloat(),
            handPaint
        )
    }

    // Returns Triple<ring, idxInRing, itemsInRing>
    private fun getRingAndIndex(i: Int): Triple<Int, Int, Int> {
        return if (i < numInner) {
            Triple(0, i, numInner)
        } else {
            Triple(1, i - numInner, numOuter)
        }
    }

    private fun getRingCount(): Int {
        return if (numOuter > 0) 2 else 1
    }

    private fun dismissMenu() {
        visibility = View.GONE
        listener?.onMenuDismissed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        Log.d("FanMenuView", "onTouchEvent: action=${event.action}, x=${event.x}, y=${event.y}, visible=$visibility, clickable=$isClickable")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(event.x - center.x, event.y - center.y)
                val inMenu = isPointInMenu(event.x, event.y)
                Log.d("FanMenuView", "ACTION_DOWN: distance=$distance, inMenu=$inMenu")
                if (distance < centerHoleRadius) {
                    // Touched the center hole
                    if (!isTransitioning) {
                        Log.d("FanMenuView", "Dismissing menu from center hole")
                        dismissMenu()
                    }
                    return true
                } else if (!inMenu) {
                    // Touched outside the fan menu
                    if (!isTransitioning) {
                        Log.d("FanMenuView", "Dismissing menu from outside fan")
                        dismissMenu()
                    }
                    return true
                }
                // Start tracking touch
                updateSelectedItem(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                Log.d("FanMenuView", "ACTION_MOVE: x=${event.x}, y=${event.y}")
                // Update selection based on current touch position
                updateSelectedItem(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                Log.d("FanMenuView", "ACTION_UP/CANCEL: selectedIndex=$selectedIndex")
                if (event.action == MotionEvent.ACTION_UP) {
                    selectedIndex?.let { index ->
                        if (index in options.indices) {
                            isTransitioning = true
                            Log.d("FanMenuView", "Option selected: $index -> ${options[index]}")
                            if (listener?.onOptionSelected(options[index]) == false) {
                                dismissMenu()
                            }
                        }
                    }
                }
                selectedIndex = null
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateSelectedItem(x: Float, y: Float) {
        val distance = hypot(x - center.x, y - center.y)
        val angle = atan2(y - center.y, x - center.x)
        val normalizedAngle = (angle + 2 * Math.PI) % (2 * Math.PI)
        val startAngle = (menuStartAngle + 2 * Math.PI) % (2 * Math.PI)
        val endAngle = (startAngle + menuFanAngle) % (2 * Math.PI)
        val inFan = if (startAngle < endAngle) {
            normalizedAngle in startAngle..endAngle
        } else {
            normalizedAngle >= startAngle || normalizedAngle <= endAngle
        }
        Log.d("FanMenuView", "updateSelectedItem: x=$x, y=$y, distance=$distance, inFan=$inFan")
        if (!inFan) {
            selectedIndex = null
            invalidate()
            return
        }
        val ring = when {
            distance < menuRadius + iconRadius -> 0 // Inner ring
            distance < menuRadius + ringSpacing + iconRadius && numOuter > 0 -> 1 // Outer ring
            else -> {
                Log.d("FanMenuView", "Touch between rings or outside")
                return // Between rings or outside
            }
        }
        val itemsInRing = if (ring == 0) numInner else numOuter
        val angleStep = menuFanAngle / itemsInRing
        val relativeAngle = if (normalizedAngle < startAngle) {
            normalizedAngle + 2 * Math.PI - startAngle
        } else {
            normalizedAngle - startAngle
        }
        val idxInRing = (relativeAngle / angleStep).toInt()
        val index = if (ring == 0) idxInRing else numInner + idxInRing
        Log.d("FanMenuView", "updateSelectedItem: ring=$ring, idxInRing=$idxInRing, index=$index, selectedIndex=$selectedIndex")
        if (index in options.indices) {
            if (selectedIndex != index) {
                selectedIndex = index
                invalidate()
                Log.d("FanMenuView", "Selection changed to $index")
            }
        }
    }

    /**
     * Returns true if the given point is within the fan menu area (any ring), false if outside.
     */
    private fun isPointInMenu(x: Float, y: Float): Boolean {
        val distance = hypot(x - center.x, y - center.y)
        val maxRadius = menuRadius + (if (numOuter > 0) ringSpacing else 0f) + iconRadius
        val minRadius = centerHoleRadius
        val angle = atan2(y - center.y, x - center.x)
        val normalizedAngle = (angle + 2 * Math.PI) % (2 * Math.PI)
        val startAngle = (menuStartAngle + 2 * Math.PI) % (2 * Math.PI)
        val endAngle = (startAngle + menuFanAngle) % (2 * Math.PI)
        val inFan = if (startAngle < endAngle) {
            normalizedAngle in startAngle..endAngle
        } else {
            normalizedAngle >= startAngle || normalizedAngle <= endAngle
        }
        return distance >= minRadius && distance <= maxRadius && inFan
    }

    fun showAt(center: PointF, options: List<Option>, listener: OnOptionSelectedListener, screenSize: PointF) {
        this.center = center
        this.options = options
        this.listener = listener
        this.screenSize = screenSize
        this.selectedIndex = null
        this.isTransitioning = false
        numInner = minOf(options.size, maxItemsInnerRing)
        numOuter = options.size - numInner

        // Calculate optimal menu angle and position
        calculateOptimalMenuAngle(center, screenSize)
        
        invalidate()
    }

    private fun calculateOptimalMenuAngle(center: PointF, screenSize: PointF) {
        val minFanAngle = Math.PI / 2 // 90 degrees
        val maxFanAngle = 2 * Math.PI // 360 degrees
        val minRadius = 300f
        val maxRadius = 500f
        val minAngleBetween = Math.PI / 8 // 22.5 degrees between items minimum
        val iconSpacing = iconRadius * 2 * 2.1 // 220% extra spacing for more padding
        val n = numInner
        var requiredFanAngle = minFanAngle
        var requiredRadius = minRadius

        if (n > 1) {
            // Calculate the minimum angle needed to avoid overlap at minRadius
            val angleBetween = max(minAngleBetween, 2 * asin(iconSpacing / (2 * minRadius)))
            val totalAngleNeeded = angleBetween * (n - 1)
            if (totalAngleNeeded <= maxFanAngle) {
                requiredFanAngle = max(minFanAngle, totalAngleNeeded)
                requiredRadius = minRadius
            } else {
                // Use full circle, increase radius to fit all items
                requiredFanAngle = maxFanAngle
                // Calculate the radius needed to fit all items in a full circle
                val neededRadius = (iconSpacing / (2 * sin(Math.PI / n))).toFloat()
                requiredRadius = neededRadius.coerceAtLeast(minRadius).coerceAtMost(maxRadius)
            }
        }

        menuFanAngle = requiredFanAngle
        menuRadius = requiredRadius

        // Calculate the optimal start angle that maximizes visible items
        val candidateAngles = listOf(
            0.0, // right
            Math.PI / 4, // down-right
            Math.PI / 2, // down
            3 * Math.PI / 4, // down-left
            Math.PI, // left
            5 * Math.PI / 4, // up-left
            3 * Math.PI / 2, // up
            7 * Math.PI / 4 // up-right
        )

        var maxVisible = -1f
        var bestStartAngle = 0.0

        for (angle in candidateAngles) {
            val visible = computeVisibleArcFraction(center, menuRadius, angle, menuFanAngle, screenSize)
            if (visible > maxVisible) {
                maxVisible = visible
                bestStartAngle = angle
            }
        }

        menuStartAngle = bestStartAngle
        // Normalize menuStartAngle to [0, 2PI)
        menuStartAngle = (menuStartAngle + 2 * Math.PI) % (2 * Math.PI)
    }

    /**
     * Returns the fraction of the arc that is visible within the screen bounds for a given start angle.
     */
    private fun computeVisibleArcFraction(center: PointF, radius: Float, startAngle: Double, fanAngle: Double, screenSize: PointF): Float {
        val steps = 32
        var visible = 0
        for (i in 0..steps) {
            val theta = startAngle + fanAngle * i / steps
            val x = center.x + (radius + iconRadius) * cos(theta).toFloat()
            val y = center.y + (radius + iconRadius) * sin(theta).toFloat()
            if (x >= 0 && x <= screenSize.x && y >= 0 && y <= screenSize.y) {
                visible++
            }
        }
        return visible.toFloat() / (steps + 1)
    }
}