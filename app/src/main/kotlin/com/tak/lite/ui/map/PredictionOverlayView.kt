package com.tak.lite.ui.map

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.tak.lite.model.ConfidenceCone
import com.tak.lite.model.LocationPrediction
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Projection

class PredictionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var projection: Projection? = null
    private var predictions: Map<String, LocationPrediction> = emptyMap()
    private var confidenceCones: Map<String, ConfidenceCone> = emptyMap()
    private var peerLocations: Map<String, org.maplibre.android.geometry.LatLng> = emptyMap()
    private var showPredictionOverlay: Boolean = true
    
    // Paint objects for different elements
    private val predictionLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.STROKE
        strokeWidth = 4f
        pathEffect = DashPathEffect(floatArrayOf(20f, 10f), 0f)
    }
    
    private val predictionDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        style = Paint.Style.FILL
    }
    
    private val confidenceConePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(30, 255, 255, 0) // More opaque yellow
        style = Paint.Style.FILL
    }
    
    private val confidenceConeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 0) // More opaque border
        style = Paint.Style.STROKE
        strokeWidth = 3f // Thicker border
    }
    
    private val confidenceTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW
        textSize = 24f
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    
    fun setProjection(projection: Projection?) {
        this.projection = projection
        invalidate()
    }
    
    fun updatePredictions(predictions: Map<String, LocationPrediction>) {
        this.predictions = predictions
        invalidate()
    }
    
    fun updateConfidenceCones(cones: Map<String, ConfidenceCone>) {
        this.confidenceCones = cones
        invalidate()
    }
    
    fun updatePeerLocations(locations: Map<String, org.maplibre.android.geometry.LatLng>) {
        this.peerLocations = locations
        invalidate()
    }
    
    fun setShowPredictionOverlay(show: Boolean) {
        this.showPredictionOverlay = show
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (projection == null || !showPredictionOverlay) return
        
        // Draw confidence cones first (behind everything)
        drawConfidenceCones(canvas)
        
        // Draw prediction lines and dots
        drawPredictions(canvas)
    }
    
    private fun drawConfidenceCones(canvas: Canvas) {
        Log.d("PredictionOverlayView", "Drawing ${confidenceCones.size} confidence cones")
        
        confidenceCones.forEach { (peerId, cone) ->
            val peerLocation = peerLocations[peerId] ?: return@forEach
            
            Log.d("PredictionOverlayView", "Drawing cone for peer $peerId: ${cone.leftBoundary.size} left points, ${cone.rightBoundary.size} right points")
            
            // Convert cone boundaries to screen coordinates, filtering out NaN values
            val leftBoundaryPoints = cone.leftBoundary.mapNotNull { latLng ->
                latLng.toMapLibreLatLngSafe()?.let { mapLibreLatLng ->
                    projection?.toScreenLocation(mapLibreLatLng)
                }
            }
            val rightBoundaryPoints = cone.rightBoundary.mapNotNull { latLng ->
                latLng.toMapLibreLatLngSafe()?.let { mapLibreLatLng ->
                    projection?.toScreenLocation(mapLibreLatLng)
                }
            }
            
            Log.d("PredictionOverlayView", "Converted to screen coordinates: ${leftBoundaryPoints.size} left, ${rightBoundaryPoints.size} right")
            
            if (leftBoundaryPoints.size >= 2 && rightBoundaryPoints.size >= 2) {
                // Create path for the cone
                val conePath = Path()
                
                // Start at the peer location
                val peerScreenPoint = projection?.toScreenLocation(peerLocation)
                if (peerScreenPoint != null) {
                    conePath.moveTo(peerScreenPoint.x, peerScreenPoint.y)
                    
                    // Add left boundary
                    leftBoundaryPoints.forEach { point ->
                        conePath.lineTo(point.x, point.y)
                    }
                    
                    // Add right boundary in reverse
                    for (i in rightBoundaryPoints.size - 1 downTo 0) {
                        val point = rightBoundaryPoints[i]
                        conePath.lineTo(point.x, point.y)
                    }
                    
                    conePath.close()
                    
                    // Draw the cone
                    canvas.drawPath(conePath, confidenceConePaint)
                    canvas.drawPath(conePath, confidenceConeBorderPaint)
                    
                    Log.d("PredictionOverlayView", "Drew cone for peer $peerId")
                }
            } else {
                Log.w("PredictionOverlayView", "Insufficient boundary points for cone: ${leftBoundaryPoints.size} left, ${rightBoundaryPoints.size} right")
            }
        }
    }
    
    private fun drawPredictions(canvas: Canvas) {
        predictions.forEach { (peerId, prediction) ->
            val peerLocation = peerLocations[peerId] ?: return@forEach
            
            // Use safe conversion to handle invalid coordinates
            val predictedLocation = prediction.predictedLocation.toMapLibreLatLngSafe() ?: return@forEach
            
            // Convert to screen coordinates
            val peerScreenPoint = projection?.toScreenLocation(peerLocation)
            val predictedScreenPoint = projection?.toScreenLocation(predictedLocation)
            
            if (peerScreenPoint != null && predictedScreenPoint != null) {
                // Draw prediction line
                canvas.drawLine(
                    peerScreenPoint.x, peerScreenPoint.y,
                    predictedScreenPoint.x, predictedScreenPoint.y,
                    predictionLinePaint
                )
                
                // Draw prediction dot
                canvas.drawCircle(
                    predictedScreenPoint.x, predictedScreenPoint.y,
                    12f, predictionDotPaint
                )
                
                // Draw prediction info
                val velocity = prediction.velocity
                if (velocity != null) {
                    val speedMph = (velocity.speed * 2.23694).toInt()
                    val infoText = "$speedMph mph"
                    val textBounds = Rect()
                    confidenceTextPaint.getTextBounds(infoText, 0, infoText.length, textBounds)
                    
                    canvas.drawText(
                        infoText,
                        predictedScreenPoint.x,
                        predictedScreenPoint.y - 20f,
                        confidenceTextPaint
                    )
                }
            }
        }
    }
} 