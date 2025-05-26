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
import kotlin.math.min
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
    private var menuFanAngle: Double = 1.5 * Math.PI
    private var menuStartAngle: Double = 5 * Math.PI / 4
    private var screenSize: PointF = PointF(0f, 0f)
    private val iconRadius = 80f
    private val centerHoleRadius = 130f
    private var isTransitioning = false
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.TRANSPARENT
        style = Paint.Style.FILL
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
    }
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    // Two-ring fan menu configuration
    private val ringSpacing = iconRadius * 1.3f
    private val iconMargin = 10f

    // Remove maxItemsInnerRing, numInner, numOuter
    // Add new data structure for rings
    private data class Ring(val startIndex: Int, val count: Int, val sectorAngle: Double)
    private var rings: List<Ring> = emptyList()

    // Constants for sector angles
    private val minSectorAngle = Math.PI / 5 // 30 degrees
    private val maxSectorAngle = Math.PI / 3 // 60 degrees
    private val fullCircle = 2 * Math.PI

    private var centerOffset: PointF = PointF(0f, 0f)
    private var menuCenterLatLng: org.maplibre.android.geometry.LatLng? = null

    override fun onDraw(canvas: Canvas) {
        Log.d("FanMenuView", "onDraw called, visibility=$visibility, options.size=${options.size}")
        super.onDraw(canvas)
        if (options.isEmpty() || rings.isEmpty()) return

        // Debug: Draw crosshair at center point
        val centerDebugPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.CYAN
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        // Horizontal line
        canvas.drawLine(center.x - 15, center.y, center.x + 15, center.y, centerDebugPaint)
        // Vertical line
        canvas.drawLine(center.x, center.y - 15, center.x, center.y + 15, centerDebugPaint)
        // Small circle
        canvas.drawCircle(center.x, center.y, 3f, centerDebugPaint.apply { style = Paint.Style.FILL })

        // Draw fan background and outlines for all sectors with gaps (exploded effect)
        val gapAngleDeg = 4f // degrees of gap between sectors
        val gapAngleRad = Math.toRadians(gapAngleDeg.toDouble())
        for ((ringIdx, ring) in rings.withIndex()) {
            val angleStep = ring.sectorAngle
            val sectorGap = gapAngleRad
            val innerRadius = centerHoleRadius + ringIdx * ringSpacing
            val outerRadius = innerRadius + ringSpacing
            Log.d("FanMenuView", "[DEBUG][onDraw] Ring $ringIdx: innerRadius=$innerRadius, outerRadius=$outerRadius, center=(${center.x}, ${center.y})")
            for (i in 0 until ring.count) {
                if (i == 0) {
                    val sectorStart = menuStartAngle + (i * angleStep) + (sectorGap / 2)
                    val sectorSweep = angleStep - sectorGap
                    val sectorEnd = sectorStart + sectorSweep
                    Log.d("FanMenuView", "[DEBUG][onDraw] Sector $i: sectorStart=$sectorStart, sectorEnd=$sectorEnd")
                }
                val sectorStart = menuStartAngle + (i * angleStep) + (sectorGap / 2)
                val sectorSweep = angleStep - sectorGap
                val startAngleDeg = Math.toDegrees(sectorStart).toFloat()
                val sweepAngleDeg = Math.toDegrees(sectorSweep).toFloat()
                val sectorPath = android.graphics.Path()
                // Outer arc
                sectorPath.arcTo(
                    center.x - outerRadius, center.y - outerRadius,
                    center.x + outerRadius, center.y + outerRadius,
                    startAngleDeg, sweepAngleDeg, false
                )
                // Inner arc (reverse)
                sectorPath.arcTo(
                    center.x - innerRadius, center.y - innerRadius,
                    center.x + innerRadius, center.y + innerRadius,
                    startAngleDeg + sweepAngleDeg, -sweepAngleDeg, false
                )
                sectorPath.close()
                // Draw filled background
                canvas.drawPath(sectorPath, backgroundPaint)
                // Draw white outline (ensure always drawn)
                val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = 6f
                }
                canvas.drawPath(sectorPath, outlinePaint)
            }
        }
        // Draw empty center (hole)
        canvas.drawCircle(center.x, center.y, centerHoleRadius, clearPaint)
        // Draw small white dot at center
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center.x, center.y, 7f, dotPaint)
        // Draw center text (example: distance and bearing)
        drawCenterText(canvas)
        // Draw selected sector background if any
        selectedIndex?.let { selIdx ->
            val (ringIdx, idxInRing, ring) = getRingAndIndex(selIdx)
            val angleStep = ring.sectorAngle
            val sectorStart = menuStartAngle + idxInRing * angleStep
            val sectorSweep = angleStep
            val innerRadius = centerHoleRadius + ringIdx * ringSpacing
            val outerRadius = innerRadius + ringSpacing
            val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255) // Light, semi-transparent
                style = Paint.Style.FILL
            }
            val path = android.graphics.Path()
            val startAngleDeg = Math.toDegrees(sectorStart).toFloat()
            val sweepAngleDeg = Math.toDegrees(sectorSweep).toFloat()
            // Outer arc
            path.arcTo(
                center.x - outerRadius, center.y - outerRadius,
                center.x + outerRadius, center.y + outerRadius,
                startAngleDeg, sweepAngleDeg, false
            )
            // Inner arc (reverse)
            path.arcTo(
                center.x - innerRadius, center.y - innerRadius,
                center.x + innerRadius, center.y + innerRadius,
                startAngleDeg + sweepAngleDeg, -sweepAngleDeg, false
            )
            path.close()
            canvas.drawPath(path, highlightPaint)
        }
        // Draw dividing lines and options for all rings
        for ((ringIdx, ring) in rings.withIndex()) {
            val angleStep = ring.sectorAngle
            for (i in 0 until ring.count) {
                val optionIdx = ring.startIndex + i
                val angle = menuStartAngle + (i + 0.5) * angleStep
                val innerRadius = centerHoleRadius + ringIdx * ringSpacing
                val outerRadius = innerRadius + ringSpacing
                val iconCenterRadius = (innerRadius + outerRadius) / 2f
                val x = center.x + iconCenterRadius * cos(angle).toFloat()
                val y = center.y + iconCenterRadius * sin(angle).toFloat()
                val maxIconRadius = (ringSpacing / 2f) - iconMargin
                val usedIconRadius = minOf(iconRadius, maxIconRadius)
                val isSelected = optionIdx == selectedIndex
                // Draw option icon
                when (val option = options[optionIdx]) {
                    is Option.Shape -> drawShapeIcon(canvas, x, y, option.shape, isSelected, usedIconRadius)
                    is Option.Color -> drawColorIcon(canvas, x, y, option.color, isSelected, usedIconRadius)
                    is Option.Delete -> drawDeleteIcon(canvas, x, y, isSelected, usedIconRadius)
                    is Option.LineStyle -> drawLineStyleIcon(canvas, x, y, option.style, isSelected, usedIconRadius)
                    is Option.Timer -> drawTimerIcon(canvas, x, y, isSelected, usedIconRadius)
                }
            }
        }
    }

    private fun drawShapeIcon(canvas: Canvas, x: Float, y: Float, shape: PointShape, highlight: Boolean, iconRadius: Float) {
        val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, iconPaint)
        val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        when (shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(x, y, iconRadius / 2, shapePaint)
            }
            PointShape.EXCLAMATION -> {
                val half = iconRadius / 2f
                val height = (half * sqrt(3.0)).toFloat()
                val path = android.graphics.Path()
                path.moveTo(x, y - height / 2)
                path.lineTo(x - half, y + height / 2)
                path.lineTo(x + half, y + height / 2)
                path.close()
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
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
                path.moveTo(x, y - height / 2)
                path.lineTo(x - half, y + height / 2)
                path.lineTo(x + half, y + height / 2)
                path.close()
                canvas.drawPath(path, shapePaint)
            }
        }
    }

    private fun drawColorIcon(canvas: Canvas, x: Float, y: Float, color: AnnotationColor, highlight: Boolean, iconRadius: Float) {
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
            strokeWidth = 2.5f
        }
        canvas.drawCircle(x, y, iconRadius, fillPaint)
        canvas.drawCircle(x, y, iconRadius, borderPaint)
    }

    private fun drawDeleteIcon(canvas: Canvas, x: Float, y: Float, highlight: Boolean, iconRadius: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.RED else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#23234B")
            style = Paint.Style.STROKE
            strokeWidth = iconRadius * 0.18f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val canWidth = iconRadius * 1.1f
        val canHeight = iconRadius * 1.25f
        val canTop = y - canHeight * 0.35f
        val canBottom = y + canHeight * 0.45f
        val canLeft = x - canWidth / 2.1f
        val canRight = x + canWidth / 2.1f
        val bodyPath = android.graphics.Path().apply {
            moveTo(canLeft + outlinePaint.strokeWidth/2, canTop + iconRadius * 0.18f)
            lineTo(canRight - outlinePaint.strokeWidth/2, canTop + iconRadius * 0.18f)
            lineTo(canRight - iconRadius * 0.10f, canBottom - outlinePaint.strokeWidth/2)
            lineTo(canLeft + iconRadius * 0.10f, canBottom - outlinePaint.strokeWidth/2)
            close()
        }
        canvas.drawPath(bodyPath, outlinePaint)
        val lidHeight = iconRadius * 0.22f
        val lidRect = android.graphics.RectF(
            canLeft + iconRadius * 0.05f,
            canTop,
            canRight - iconRadius * 0.05f,
            canTop + lidHeight
        )
        canvas.drawRoundRect(lidRect, lidHeight/2, lidHeight/2, outlinePaint)
        val handleRadius = iconRadius * 0.28f
        val handleCenterY = canTop + lidHeight/2
        val handleRect = android.graphics.RectF(
            x - handleRadius,
            handleCenterY - handleRadius,
            x + handleRadius,
            handleCenterY + handleRadius
        )
        canvas.drawArc(handleRect, 180f, 180f, false, outlinePaint)
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

    private fun drawLineStyleIcon(canvas: Canvas, x: Float, y: Float, style: com.tak.lite.model.LineStyle, highlight: Boolean, iconRadius: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            this.style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            this.style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            this.style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        val yLine = y
        val xStart = x - iconRadius * 0.7f
        val xEnd = x + iconRadius * 0.7f
        if (style == com.tak.lite.model.LineStyle.DASHED) {
            paint.pathEffect = android.graphics.DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }
        canvas.drawLine(xStart, yLine, xEnd, yLine, paint)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 16f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(
            if (style == com.tak.lite.model.LineStyle.SOLID) "Solid" else "Dashed",
            x,
            y + iconRadius * 0.8f,
            textPaint
        )
    }

    private fun drawTimerIcon(canvas: Canvas, x: Float, y: Float, highlight: Boolean, iconRadius: Float) {
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.YELLOW else Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(x, y, iconRadius, bgPaint)
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (highlight) Color.WHITE else Color.DKGRAY
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
        }
        canvas.drawCircle(x, y, iconRadius, borderPaint)
        val timerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 3.5f
        }
        val timerRadius = iconRadius * 0.6f
        canvas.drawCircle(x, y, timerRadius, timerPaint)
        val handPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
        }
        val hourHandLength = timerRadius * 0.5f
        val hourAngle = -Math.PI / 4
        canvas.drawLine(
            x,
            y,
            x + (hourHandLength * cos(hourAngle)).toFloat(),
            y + (hourHandLength * sin(hourAngle)).toFloat(),
            handPaint
        )
        val minuteHandLength = timerRadius * 0.8f
        val minuteAngle = Math.PI / 2
        canvas.drawLine(
            x,
            y,
            x + (minuteHandLength * cos(minuteAngle)).toFloat(),
            y + (minuteHandLength * sin(minuteAngle)).toFloat(),
            handPaint
        )
    }

    // Returns Triple<ringIdx, idxInRing, Ring>
    private fun getRingAndIndex(optionIndex: Int): Triple<Int, Int, Ring> {
        var idx = optionIndex
        for ((ringIdx, ring) in rings.withIndex()) {
            if (idx < ring.count) return Triple(ringIdx, idx, ring)
            idx -= ring.count
        }
        throw IndexOutOfBoundsException()
    }

    private fun getRingCount(): Int = rings.size

    private fun dismissMenu() {
        Log.d("FanMenuView", "dismissMenu called")
        visibility = View.GONE
        listener?.onMenuDismissed()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val adjustedX = event.x - centerOffset.x
        val adjustedY = event.y - centerOffset.y
        Log.d("FanMenuView", "onTouchEvent: action=${event.action}, visible=$visibility, clickable=$isClickable, event.x=${event.x}, event.y=${event.y}, adjustedX=$adjustedX, adjustedY=$adjustedY, center=$center")
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val distance = hypot(adjustedX - center.x, adjustedY - center.y)
                val inMenu = isPointInMenu(adjustedX, adjustedY)
                Log.d("FanMenuView", "ACTION_DOWN: distance=$distance, inMenu=$inMenu")
                if (distance < centerHoleRadius) {
                    if (!isTransitioning) {
                        Log.d("FanMenuView", "Dismissing menu from center hole")
                        dismissMenu()
                    }
                    return true
                } else if (!inMenu) {
                    if (!isTransitioning) {
                        Log.d("FanMenuView", "Dismissing menu from outside fan")
                        dismissMenu()
                    }
                    return true
                }
                updateSelectedItem(adjustedX, adjustedY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                Log.d("FanMenuView", "ACTION_MOVE: x=$adjustedX, y=$adjustedY")
                updateSelectedItem(adjustedX, adjustedY)
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
        Log.d("FanMenuView", "updateSelectedItem: x=$x, y=$y, center=$center, offset=(${x-center.x}, ${y-center.y})")
        val distance = hypot(x - center.x, y - center.y)
        // Get standard angle from atan2
        val rawAngle = atan2(y - center.y, x - center.x)
        // Transform to match sector coordinate system
        // First convert to positive angles (0 to 2π)
        val normalizedAngle = (rawAngle + 2 * Math.PI) % (2 * Math.PI)
        val gapAngleDeg = 4f // Must match drawing
        val gapAngleRad = Math.toRadians(gapAngleDeg.toDouble())
        
        // Debug: Log the exact coordinates and offsets
        val offsetFromCenter = PointF(x - center.x, y - center.y)
        Log.d("FanMenuView", "[DEBUG] Touch at x=$x, y=$y")
        Log.d("FanMenuView", "[DEBUG] Center at x=${center.x}, y=${center.y}")
        Log.d("FanMenuView", "[DEBUG] Offset from center: dx=${offsetFromCenter.x}, dy=${offsetFromCenter.y}")
        Log.d("FanMenuView", String.format("[DEBUG] Distance=%.2f, rawAngle=%.2f°, normalizedAngle=%.2f°", 
            distance, 
            Math.toDegrees(rawAngle.toDouble()),
            Math.toDegrees(normalizedAngle)
        ))

        var found = false
        for ((ringIdx, ring) in rings.withIndex()) {
            val angleStep = ring.sectorAngle
            val innerRadius = centerHoleRadius + ringIdx * ringSpacing
            val outerRadius = innerRadius + ringSpacing
            Log.d("FanMenuView", "[DEBUG] Ring $ringIdx: innerRadius=$innerRadius, outerRadius=$outerRadius")
            for (i in 0 until ring.count) {
                // Use the same sectorStart and sectorSweep as in onDraw, without the 2π normalization
                val sectorStart = menuStartAngle + i * angleStep + gapAngleRad / 2
                val sectorSweep = angleStep - gapAngleRad
                val sectorEnd = sectorStart + sectorSweep
                
                // Check if normalizedAngle is within [sectorStart, sectorEnd], after normalizing both
                val normalizedSectorStart = (sectorStart + 2 * Math.PI) % (2 * Math.PI)
                val normalizedSectorEnd = (sectorEnd + 2 * Math.PI) % (2 * Math.PI)
                
                val inSector = if (normalizedSectorStart < normalizedSectorEnd) {
                    normalizedAngle in normalizedSectorStart..normalizedSectorEnd
                } else {
                    normalizedAngle >= normalizedSectorStart || normalizedAngle <= normalizedSectorEnd
                }
                
                Log.d("FanMenuView", String.format("[DEBUG] Sector $i: sectorStart=%.2f°, sectorEnd=%.2f°, normalizedStart=%.2f°, normalizedEnd=%.2f°, inSector=$inSector",
                    Math.toDegrees(sectorStart),
                    Math.toDegrees(sectorEnd),
                    Math.toDegrees(normalizedSectorStart),
                    Math.toDegrees(normalizedSectorEnd)
                ))
                
                val inDonut = distance in innerRadius..outerRadius && inSector
                if (!inDonut) continue
                val index = ring.startIndex + i
                if (index in options.indices) {
                    if (selectedIndex != index) {
                        selectedIndex = index
                        invalidate()
                        Log.d("FanMenuView", "Selection changed to $index")
                    }
                }
                found = true
                break
            }
            if (found) break
        }
        if (!found) {
            selectedIndex = null
            invalidate()
        }
    }

    /**
     * Returns true if the given point is within the fan menu area (any ring), false if outside.
     */
    private fun isPointInMenu(x: Float, y: Float): Boolean {
        val distance = hypot(x - center.x, y - center.y)
        val maxRadius = centerHoleRadius + (if (rings.size > 1) ringSpacing else 0f) + iconRadius
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

    fun reset() {
        listener = null
        options = emptyList()
        selectedIndex = null
        isTransitioning = false
        rings = emptyList()
        visibility = View.GONE
        invalidate()
    }

    fun showAt(center: PointF, options: List<Option>, listener: OnOptionSelectedListener, screenSize: PointF, menuLatLng: org.maplibre.android.geometry.LatLng? = null) {
        Log.d("FanMenuView", "showAt: center=$center, screenSize=$screenSize, options=$options, menuLatLng=$menuLatLng")
        reset() // Defensive: always clear state before showing
        val viewLocation = IntArray(2)
        this.getLocationOnScreen(viewLocation)
        centerOffset = PointF(viewLocation[0].toFloat(), viewLocation[1].toFloat())
        this.center = center // Keep center in parent/screen coordinates
        this.options = options
        this.listener = listener
        this.screenSize = screenSize
        this.selectedIndex = null
        this.isTransitioning = false
        this.menuCenterLatLng = menuLatLng
        // --- Enhanced: calculate optimal angle and first ring count ---
        val layout = calculateOptimalMenuAngle(center, screenSize, options.size)
        this.rings = computeRings(options.size, layout.firstRingCount)
        val widestRing = rings.maxByOrNull { it.count } ?: rings.first()
        val gapAngleDeg = 4f
        val gapAngleRad = Math.toRadians(gapAngleDeg.toDouble())
        val totalCoveredAngle = widestRing.count * widestRing.sectorAngle + widestRing.count * gapAngleRad
        menuStartAngle = layout.startAngle
        menuFanAngle = totalCoveredAngle // Ensure hit-testing matches drawing
        invalidate()
    }

    // Compute rings: prefer maxSectorAngle, shrink to minSectorAngle, overflow to new ring if needed
    private fun computeRings(optionCount: Int, firstRingCount: Int? = null): List<Ring> {
        val rings = mutableListOf<Ring>()
        var remaining = optionCount
        var startIdx = 0
        val gapAngleDeg = 4f // Must match drawing and hit-testing
        val gapAngleRad = Math.toRadians(gapAngleDeg.toDouble())
        var firstRing = true
        while (remaining > 0) {
            var nThisRing = if (firstRing && firstRingCount != null) minOf(firstRingCount, remaining) else remaining
            firstRing = false
            while (nThisRing > 0) {
                val availableAngle = 2 * Math.PI - nThisRing * gapAngleRad
                var sectorAngle = availableAngle / nThisRing
                if (sectorAngle < minSectorAngle) {
                    nThisRing--
                    continue
                }
                // Clamp sectorAngle to maxSectorAngle, even if that means the ring is not a full circle
                sectorAngle = sectorAngle.coerceAtMost(maxSectorAngle)
                rings.add(Ring(startIdx, nThisRing, sectorAngle))
                startIdx += nThisRing
                remaining -= nThisRing
                break
            }
            if (nThisRing == 0) {
                // Fallback: force at least one in this ring with maxSectorAngle
                rings.add(Ring(startIdx, 1, maxSectorAngle))
                startIdx += 1
                remaining -= 1
            }
        }
        return rings
    }

    /**
     * Returns the optimal menu start angle and number of items in the first ring so that all icons are on-screen.
     * Prefers to open toward the center of the screen.
     */
    private data class FanMenuLayout(val startAngle: Double, val firstRingCount: Int)
    private fun calculateOptimalMenuAngle(center: PointF, screenSize: PointF, optionCount: Int): FanMenuLayout {
        val screenCenter = PointF(screenSize.x / 2f, screenSize.y / 2f)
        val dx = screenCenter.x - center.x
        val dy = screenCenter.y - center.y
        val preferredAngle = atan2(dy, dx) // radians, 0 = right, pi/2 = down
        val gapAngleDeg = 4f
        val gapAngleRad = Math.toRadians(gapAngleDeg.toDouble())
        val minFirstRing = 1
        val maxFirstRing = optionCount
        val innerRadius = centerHoleRadius
        val outerRadius = innerRadius + ringSpacing
        val iconMargin = 10f
        val iconR = minOf(iconRadius, (ringSpacing / 2f) - iconMargin)
        // Try largest possible first ring down to 1
        for (n in maxFirstRing downTo minFirstRing) {
            val availableAngle = 2 * Math.PI - n * gapAngleRad
            var sectorAngle = availableAngle / n
            if (sectorAngle < minSectorAngle) continue
            sectorAngle = sectorAngle.coerceAtMost(maxSectorAngle)
            val totalFanAngle = n * sectorAngle + n * gapAngleRad
            // Try centering the fan on preferredAngle, but allow shifting if needed
            val candidateAngles = listOf(
                preferredAngle - totalFanAngle / 2.0,
                preferredAngle - totalFanAngle / 2.0 + Math.PI / 12,
                preferredAngle - totalFanAngle / 2.0 - Math.PI / 12,
                0.0, // fallback: right
                Math.PI / 2, // down
                Math.PI, // left
                -Math.PI / 2 // up
            )
            for (candidateStart in candidateAngles) {
                var allOnScreen = true
                for (i in 0 until n) {
                    val angle = candidateStart + (i + 0.5) * sectorAngle + gapAngleRad * (i + 0.5)
                    val r = (innerRadius + outerRadius) / 2f
                    val x = center.x + r * cos(angle).toFloat()
                    val y = center.y + r * sin(angle).toFloat()
                    // Check if icon is fully on screen
                    if (x - iconR < 0f || x + iconR > screenSize.x || y - iconR < 0f || y + iconR > screenSize.y) {
                        allOnScreen = false
                        break
                    }
                }
                if (allOnScreen) {
                    return FanMenuLayout(candidateStart, n)
                }
            }
        }
        // Fallback: open right with 1 item
        return FanMenuLayout(0.0, 1)
    }

    private fun drawCenterText(canvas: Canvas) {
        // Draw coordinates (bottom arc) and distance (top arc) as curved text
        val latLng = menuCenterLatLng ?: return
        val lat = latLng.latitude
        val lon = latLng.longitude
        val coordStr = String.format("%.5f, %.5f", lat, lon)
        // Get user location from shared prefs
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val userLat = prefs.getFloat("last_lat", Float.NaN).toDouble()
        val userLon = prefs.getFloat("last_lon", Float.NaN).toDouble()
        val distStr = if (!userLat.isNaN() && !userLon.isNaN()) {
            val distMeters = haversine(lat, lon, userLat, userLon)
            val distMiles = distMeters / 1609.344
            String.format("%.1f mi away", distMiles)
        } else null
        // Draw white outline around center hole
        val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawCircle(center.x, center.y, centerHoleRadius, outlinePaint)
        // Draw small white dot at center
        val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
        }
        canvas.drawCircle(center.x, center.y, 7f, dotPaint)
        // Draw coordinates on bottom arc
        val radius = centerHoleRadius - 38f
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            textSize = 30f
            style = Paint.Style.FILL
            textAlign = Paint.Align.CENTER
        }
        val bottomArc = android.graphics.Path()
        bottomArc.addArc(
            center.x - radius,
            center.y - radius,
            center.x + radius,
            center.y + radius,
            180f, // Start at left
            180f  // Sweep to right (bottom half)
        )
        canvas.drawTextOnPath(coordStr, bottomArc, 0f, 0f, textPaint)
        // Draw distance on top arc (if available)
        distStr?.let {
            val topArc = android.graphics.Path()
            topArc.addArc(
                center.x - radius,
                center.y - radius,
                center.x + radius,
                center.y + radius,
                180f, // Start at left
                -180f // Sweep to right (top half, reversed)
            )
            canvas.drawTextOnPath(it, topArc, 0f, 12f, textPaint)
        }
    }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0 // meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return R * c
    }
}