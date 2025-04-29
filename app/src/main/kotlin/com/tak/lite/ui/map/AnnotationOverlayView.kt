package com.tak.lite.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import com.google.android.gms.maps.Projection
import com.tak.lite.data.model.Annotation
import com.tak.lite.data.model.AnnotationColor
import com.tak.lite.data.model.AnnotationShape
import com.tak.lite.data.model.AnnotationType

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
    private var annotations: List<Annotation> = emptyList()
    
    fun setProjection(projection: Projection) {
        this.projection = projection
        invalidate()
    }
    
    fun updateAnnotations(annotations: List<Annotation>) {
        this.annotations = annotations
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        projection?.let { proj ->
            annotations.forEach { annotation ->
                when (annotation.type) {
                    AnnotationType.POINT -> drawPoint(canvas, proj, annotation)
                    AnnotationType.LINE -> drawLine(canvas, proj, annotation)
                    AnnotationType.AREA -> drawArea(canvas, proj, annotation)
                }
            }
        }
    }
    
    private fun drawPoint(canvas: Canvas, projection: Projection, annotation: Annotation) {
        val point = projection.toScreenLocation(annotation.points.first())
        val color = getColor(annotation.color)
        
        paint.color = color
        fillPaint.color = color
        
        when (annotation.shape) {
            AnnotationShape.CIRCLE -> {
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 20f, paint)
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 15f, fillPaint)
            }
            AnnotationShape.EXCLAMATION -> {
                // Draw exclamation mark
                canvas.drawLine(
                    point.x.toFloat(),
                    point.y.toFloat() - 20f,
                    point.x.toFloat(),
                    point.y.toFloat() + 10f,
                    paint
                )
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat() + 15f, 5f, fillPaint)
            }
            null -> {
                // Default to circle if shape is null
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 20f, paint)
                canvas.drawCircle(point.x.toFloat(), point.y.toFloat(), 15f, fillPaint)
            }
        }
    }
    
    private fun drawLine(canvas: Canvas, projection: Projection, annotation: Annotation) {
        if (annotation.points.size < 2) return
        
        val path = Path()
        val firstPoint = projection.toScreenLocation(annotation.points.first())
        path.moveTo(firstPoint.x.toFloat(), firstPoint.y.toFloat())
        
        annotation.points.drop(1).forEach { point ->
            val screenPoint = projection.toScreenLocation(point)
            path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
        }
        
        paint.color = getColor(annotation.color)
        canvas.drawPath(path, paint)
    }
    
    private fun drawArea(canvas: Canvas, projection: Projection, annotation: Annotation) {
        if (annotation.points.size < 2) return
        
        val path = Path()
        val firstPoint = projection.toScreenLocation(annotation.points.first())
        path.moveTo(firstPoint.x.toFloat(), firstPoint.y.toFloat())
        
        annotation.points.drop(1).forEach { point ->
            val screenPoint = projection.toScreenLocation(point)
            path.lineTo(screenPoint.x.toFloat(), screenPoint.y.toFloat())
        }
        path.close()
        
        paint.color = getColor(annotation.color)
        fillPaint.color = getColor(annotation.color)
        fillPaint.alpha = 64 // 25% opacity
        
        canvas.drawPath(path, paint)
        canvas.drawPath(path, fillPaint)
    }
    
    private fun getColor(color: AnnotationColor): Int {
        return when (color) {
            AnnotationColor.GREEN -> Color.GREEN
            AnnotationColor.YELLOW -> Color.YELLOW
            AnnotationColor.RED -> Color.RED
            AnnotationColor.BLACK -> Color.BLACK
        }
    }
} 