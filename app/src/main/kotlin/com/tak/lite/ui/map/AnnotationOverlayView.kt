package com.tak.lite.ui.map

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.google.android.gms.maps.Projection
import com.google.android.gms.maps.model.LatLng
import com.tak.lite.data.model.AnnotationType
import com.tak.lite.data.model.toGoogleLatLng
import com.tak.lite.data.model.toGoogleLatLngs
import com.tak.lite.data.model.type
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape

class AnnotationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 8f
    }
    
    private val fillPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
    }
    
    private var projection: Projection? = null
    private var annotations: List<MapAnnotation> = emptyList()
    private var currentZoom: Float = 0f
    
    interface OnPoiLongPressListener {
        fun onPoiLongPressed(poiId: String, screenPosition: PointF)
    }
    var poiLongPressListener: OnPoiLongPressListener? = null
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var longPressStartTime: Long = 0
    private var longPressCandidate: MapAnnotation.PointOfInterest? = null
    private var longPressDownPos: PointF? = null
    
    fun setProjection(projection: Projection) {
        this.projection = projection
        invalidate()
    }
    
    fun updateAnnotations(annotations: List<MapAnnotation>) {
        this.annotations = annotations
        invalidate()
    }
    
    fun setZoom(zoom: Float) {
        this.currentZoom = zoom
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (projection == null) return

        annotations.forEach { annotation ->
            val point = annotation.toGoogleLatLng().let { latLng ->
                projection?.toScreenLocation(latLng)
            }
            if (point == null) return@forEach
            val pointF = PointF(point.x.toFloat(), point.y.toFloat())
            when (annotation) {
                is MapAnnotation.PointOfInterest -> {
                    drawPoint(canvas, pointF, annotation)
                }
                is MapAnnotation.Line -> {
                    // Draw lines between all points
                    val latLngs = annotation.points.map { it.toGoogleLatLng() }
                    if (latLngs.size >= 2) {
                        val screenPoints = latLngs.mapNotNull { projection?.toScreenLocation(it) }
                        for (i in 0 until screenPoints.size - 1) {
                            val p1 = PointF(screenPoints[i].x.toFloat(), screenPoints[i].y.toFloat())
                            val p2 = PointF(screenPoints[i + 1].x.toFloat(), screenPoints[i + 1].y.toFloat())
                            drawLine(canvas, p1, p2, annotation)
                        }
                    }
                }
                is MapAnnotation.Area -> {
                    val centerPoint = projection?.toScreenLocation(annotation.center.toGoogleLatLng())
                    if (centerPoint != null) {
                        drawArea(canvas, centerPoint, annotation)
                    }
                }
                is MapAnnotation.Deletion -> {
                    // Do nothing for deletions
                }
            }
        }
    }
    
    private fun drawPoint(canvas: Canvas, point: PointF, annotation: MapAnnotation.PointOfInterest) {
        paint.color = annotation.color.toColor()
        
        when (annotation.shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(point.x, point.y, 30f, paint)
            }
            PointShape.EXCLAMATION -> {
                // Draw filled triangle with selected color
                val half = 30f
                val height = (half * Math.sqrt(3.0)).toFloat()
                val path = android.graphics.Path()
                path.moveTo(point.x, point.y - height / 2) // Top
                path.lineTo(point.x - half, point.y + height / 2) // Bottom left
                path.lineTo(point.x + half, point.y + height / 2) // Bottom right
                path.close()
                val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = annotation.color.toColor()
                    style = Paint.Style.FILL
                }
                canvas.drawPath(path, fillPaint)
                // Draw thinner white exclamation mark inside triangle
                val exMarkWidth = 6f
                val exMarkTop = point.y - height / 6
                val exMarkBottom = point.y + height / 6
                val exMarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.STROKE
                    strokeWidth = exMarkWidth
                    strokeCap = Paint.Cap.ROUND
                }
                canvas.drawLine(point.x, exMarkTop, point.x, exMarkBottom, exMarkPaint)
                val dotRadius = exMarkWidth * 0.6f
                val dotCenterY = exMarkBottom + dotRadius * 2.0f
                val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    style = Paint.Style.FILL
                }
                canvas.drawCircle(point.x, dotCenterY, dotRadius, dotPaint)
            }
            PointShape.SQUARE -> {
                val half = 30f
                canvas.drawRect(point.x - half, point.y - half, point.x + half, point.y + half, paint)
            }
            PointShape.TRIANGLE -> {
                val half = 30f
                val height = (half * Math.sqrt(3.0)).toFloat()
                val path = android.graphics.Path()
                path.moveTo(point.x, point.y - height / 2) // Top
                path.lineTo(point.x - half, point.y + height / 2) // Bottom left
                path.lineTo(point.x + half, point.y + height / 2) // Bottom right
                path.close()
                canvas.drawPath(path, paint)
            }
        }
    }
    
    private fun drawLine(canvas: Canvas, point1: PointF, point2: PointF, annotation: MapAnnotation.Line) {
        paint.color = annotation.color.toColor()
        
        val path = Path()
        path.moveTo(point1.x, point1.y)
        path.lineTo(point2.x, point2.y)
        canvas.drawPath(path, paint)
    }
    
    private fun drawArea(canvas: Canvas, center: Point, annotation: MapAnnotation.Area) {
        paint.color = annotation.color.toColor()
        fillPaint.color = annotation.color.toColor()
        
        val centerPointF = PointF(center.x.toFloat(), center.y.toFloat())
        val radius = annotation.radius * currentZoom
        canvas.drawCircle(centerPointF.x, centerPointF.y, radius.toFloat(), fillPaint)
        canvas.drawCircle(centerPointF.x, centerPointF.y, radius.toFloat(), paint)
    }
    
    private fun AnnotationColor.toColor(): Int {
        return when (this) {
            AnnotationColor.GREEN -> Color.GREEN
            AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
            AnnotationColor.RED -> Color.RED
            AnnotationColor.BLACK -> Color.BLACK
        }
    }
    
    private fun Int.withAlpha(alpha: Int): Int {
        return Color.argb(alpha, Color.red(this), Color.green(this), Color.blue(this))
    }

    private fun metersToEquatorPixels(meters: Double, zoom: Float): Double {
        val earthCircumference = 40075016.686 // Earth's circumference in meters at the equator
        val pixelsPerMeter = 256.0 * Math.pow(2.0, zoom.toDouble()) / earthCircumference
        return meters * pixelsPerMeter
    }

    private fun MapAnnotation.toGoogleLatLng(): LatLng {
        return when (this) {
            is MapAnnotation.PointOfInterest -> this.position.toGoogleLatLng()
            is MapAnnotation.Line -> this.points.first().toGoogleLatLng()
            is MapAnnotation.Area -> this.center.toGoogleLatLng()
            is MapAnnotation.Deletion -> throw IllegalArgumentException("Deletion has no LatLng")
        }
    }

    private fun MapAnnotation.toGoogleLatLngs(): List<LatLng> {
        return when (this) {
            is MapAnnotation.PointOfInterest -> listOf(this.position.toGoogleLatLng())
            is MapAnnotation.Line -> this.points.map { it.toGoogleLatLng() }
            is MapAnnotation.Area -> listOf(this.center.toGoogleLatLng())
            is MapAnnotation.Deletion -> emptyList()
        }
    }

    private val MapAnnotation.type: AnnotationType
        get() = when (this) {
            is MapAnnotation.PointOfInterest -> AnnotationType.POINT
            is MapAnnotation.Line -> AnnotationType.LINE
            is MapAnnotation.Area -> AnnotationType.AREA
            is MapAnnotation.Deletion -> throw IllegalArgumentException("Deletion has no AnnotationType")
        }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val poi = findPoiAt(event.x, event.y)
                if (poi != null) {
                    longPressCandidate = poi
                    longPressDownPos = PointF(event.x, event.y)
                    longPressHandler = Handler(Looper.getMainLooper())
                    longPressRunnable = Runnable {
                        poiLongPressListener?.onPoiLongPressed(poi.id, longPressDownPos!!)
                        longPressCandidate = null
                    }
                    longPressHandler?.postDelayed(longPressRunnable!!, 500)
                    return true // Intercept only if touching a POI
                } else {
                    longPressCandidate = null
                    return false // Let the map handle the event
                }
            }
            MotionEvent.ACTION_MOVE -> {
                longPressDownPos?.let { down ->
                    if (Math.hypot((event.x - down.x).toDouble(), (event.y - down.y).toDouble()) > 40) {
                        longPressHandler?.removeCallbacks(longPressRunnable!!)
                        longPressCandidate = null
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressHandler?.removeCallbacks(longPressRunnable!!)
                longPressCandidate = null
            }
        }
        return longPressCandidate != null // Only consume if interacting with a POI
    }

    private fun findPoiAt(x: Float, y: Float): MapAnnotation.PointOfInterest? {
        // Only check POIs
        val pois = annotations.filterIsInstance<MapAnnotation.PointOfInterest>()
        for (poi in pois) {
            val point = projection?.toScreenLocation(poi.position.toGoogleLatLng()) ?: continue
            val dx = x - point.x
            val dy = y - point.y
            if (Math.hypot(dx.toDouble(), dy.toDouble()) < 40) {
                return poi
            }
        }
        return null
    }
} 