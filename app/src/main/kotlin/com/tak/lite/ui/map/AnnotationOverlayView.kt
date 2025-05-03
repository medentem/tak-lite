package com.tak.lite.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
            }
        }
    }
    
    private fun drawPoint(canvas: Canvas, point: PointF, annotation: MapAnnotation.PointOfInterest) {
        paint.color = annotation.color.toColor()
        
        when (annotation.shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(point.x, point.y, 20f, paint)
            }
            PointShape.EXCLAMATION -> {
                // Draw exclamation mark
                canvas.drawLine(
                    point.x,
                    point.y - 20f,
                    point.x,
                    point.y + 20f,
                    paint
                )
                canvas.drawCircle(point.x, point.y + 25f, 5f, paint)
            }
            PointShape.SQUARE -> {
                val half = 20f
                canvas.drawRect(point.x - half, point.y - half, point.x + half, point.y + half, paint)
            }
            PointShape.TRIANGLE -> {
                val half = 20f
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
            AnnotationColor.YELLOW -> Color.YELLOW
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
            else -> throw IllegalArgumentException("Unsupported annotation type")
        }
    }

    private fun MapAnnotation.toGoogleLatLngs(): List<LatLng> {
        return when (this) {
            is MapAnnotation.PointOfInterest -> listOf(this.position.toGoogleLatLng())
            is MapAnnotation.Line -> this.points.map { it.toGoogleLatLng() }
            is MapAnnotation.Area -> listOf(this.center.toGoogleLatLng())
            else -> throw IllegalArgumentException("Unsupported annotation type")
        }
    }

    private val MapAnnotation.type: AnnotationType
        get() = when (this) {
            is MapAnnotation.PointOfInterest -> AnnotationType.POINT
            is MapAnnotation.Line -> AnnotationType.LINE
            is MapAnnotation.Area -> AnnotationType.AREA
            else -> throw IllegalArgumentException("Unsupported annotation type")
        }
} 