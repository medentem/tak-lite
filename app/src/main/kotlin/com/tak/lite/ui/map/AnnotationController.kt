package com.tak.lite.ui.map

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.view.View
import android.widget.Toast
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationViewModel
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

class AnnotationController(
    private val context: Context,
    private val binding: ActivityMainBinding,
    private val annotationViewModel: AnnotationViewModel,
    private val fanMenuView: FanMenuView,
    private val annotationOverlayView: AnnotationOverlayView,
    private val onAnnotationChanged: (() -> Unit)? = null
) {
    private var pendingPoiLatLng: LatLng? = null
    var editingPoiId: String? = null
    var isLineDrawingMode: Boolean = false
    var tempLinePoints: MutableList<LatLng> = mutableListOf()
    private lateinit var lineToolConfirmButton: View
    private lateinit var lineToolCancelButton: View
    private lateinit var lineToolButtonFrame: View
    private lateinit var lineToolLabel: View
    private val poiMarkers = mutableMapOf<String, Marker>()
    private val areaPolygons = mutableMapOf<String, Polygon>()
    private var lastOverlayProjection: org.maplibre.android.maps.Projection? = null
    private var lastOverlayAnnotations: List<MapAnnotation> = emptyList()
    var mapController: MapController? = null

    // Overlay and menu setup
    fun setupAnnotationOverlay(mapLibreMap: MapLibreMap?) {
        mapLibreMap?.addOnCameraMoveListener {
            annotationOverlayView.setProjection(mapLibreMap.projection)
            annotationOverlayView.invalidate()
        }
        annotationOverlayView.setProjection(mapLibreMap?.projection)
        annotationOverlayView.invalidate()
    }

    fun setupPoiLongPressListener() {
        annotationOverlayView.poiLongPressListener = object : AnnotationOverlayView.OnPoiLongPressListener {
            override fun onPoiLongPressed(poiId: String, screenPosition: PointF) {
                editingPoiId = poiId
                showPoiEditMenu(screenPosition, poiId)
            }
            override fun onLineLongPressed(lineId: String, screenPosition: PointF) {
                showLineEditMenu(screenPosition, lineId)
            }
        }
    }

    fun setupMapLongPress(mapLibreMap: MapLibreMap) {
        mapLibreMap.addOnMapLongClickListener { latLng ->
            val projection = mapLibreMap.projection
            val point = projection.toScreenLocation(latLng)
            val center = PointF(point.x, point.y)
            pendingPoiLatLng = latLng
            showFanMenu(center)
            true
        }
    }

    // POI/line/area editing
    fun showPoiEditMenu(center: PointF, poiId: String) {
        val poi = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.PointOfInterest>().find { it.id == poiId } ?: return
        val options = listOf(
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE),
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK),
            FanMenuView.Option.Timer(poiId),
            FanMenuView.Option.Delete(poiId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option) {
                when (option) {
                    is FanMenuView.Option.Shape -> updatePoiShape(poi, option.shape)
                    is FanMenuView.Option.Color -> updatePoiColor(poiId, option.color)
                    is FanMenuView.Option.Timer -> setAnnotationExpiration(poiId)
                    is FanMenuView.Option.Delete -> deletePoi(option.id)
                    else -> {}
                }
                fanMenuView.visibility = View.GONE
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    fun updatePoiShape(poi: MapAnnotation.PointOfInterest, shape: PointShape) {
        annotationViewModel.updatePointOfInterest(poi.id, newShape = shape)
    }

    fun updatePoiColor(poiId: String, color: AnnotationColor) {
        annotationViewModel.updatePointOfInterest(poiId, newColor = color)
    }

    fun deletePoi(poiId: String) {
        val marker = poiMarkers[poiId]
        marker?.let { (annotationOverlayView.context as? MapLibreMap)?.removeAnnotation(it) }
        annotationViewModel.removeAnnotation(poiId)
    }

    fun showLineEditMenu(center: PointF, lineId: String) {
        val line = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Line>().find { it.id == lineId } ?: return
        val options = listOf(
            FanMenuView.Option.LineStyle(
                if (line.style == LineStyle.SOLID) LineStyle.DASHED else LineStyle.SOLID
            ),
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK),
            FanMenuView.Option.Timer(lineId),
            FanMenuView.Option.Delete(lineId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option) {
                when (option) {
                    is FanMenuView.Option.LineStyle -> updateLineStyle(line, option.style)
                    is FanMenuView.Option.Color -> updateLineColor(line, option.color)
                    is FanMenuView.Option.Timer -> setAnnotationExpiration(lineId)
                    is FanMenuView.Option.Delete -> deletePoi(option.id)
                    else -> {}
                }
                annotationOverlayView.setTempLinePoints(null)
                fanMenuView.visibility = View.GONE
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    fun updateLineStyle(line: MapAnnotation.Line, newStyle: LineStyle) {
        annotationViewModel.updateLine(line.id, newStyle = newStyle)
    }

    fun updateLineColor(line: MapAnnotation.Line, newColor: AnnotationColor) {
        annotationViewModel.updateLine(line.id, newColor = newColor)
    }

    // Line drawing
    fun updateLineToolConfirmState() {
        if (tempLinePoints.size >= 2) {
            lineToolConfirmButton.isEnabled = true
            lineToolConfirmButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#388E3C")) // Green
        } else {
            lineToolConfirmButton.isEnabled = false
            lineToolConfirmButton.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#888888")) // Gray
        }
    }

    private fun finishLineDrawing(cancel: Boolean) {
        if (!cancel && tempLinePoints.size >= 2) {
            annotationViewModel.addLine(tempLinePoints.toList())
            Toast.makeText(context, "Line added!", Toast.LENGTH_SHORT).show()
        }
        isLineDrawingMode = false
        tempLinePoints.clear()
        annotationOverlayView.setTempLinePoints(null)
        lineToolButtonFrame.visibility = View.VISIBLE
        lineToolLabel.visibility = View.VISIBLE
        lineToolCancelButton.visibility = View.GONE
        lineToolConfirmButton.visibility = View.GONE
    }

    // Rendering overlays
    fun renderAllAnnotations(mapLibreMap: MapLibreMap?) {
        // Remove all existing overlays
        poiMarkers.values.forEach { mapLibreMap?.removeAnnotation(it) }
        poiMarkers.clear()
        areaPolygons.values.forEach { mapLibreMap?.removeAnnotation(it) }
        areaPolygons.clear()
        
        // Update the annotation overlay with current annotations
        val state = annotationViewModel.uiState.value
        annotationOverlayView.updateAnnotations(state.annotations)
        annotationOverlayView.invalidate()
    }

    fun syncAnnotationOverlayView(mapLibreMap: MapLibreMap?) {
        val currentProjection = mapLibreMap?.projection
        val currentAnnotations = annotationViewModel.uiState.value.annotations
        var changed = false
        if (currentProjection != lastOverlayProjection) {
            annotationOverlayView.setProjection(currentProjection)
            lastOverlayProjection = currentProjection
            changed = true
        }
        annotationOverlayView.updateAnnotations(currentAnnotations)
        lastOverlayAnnotations = currentAnnotations
        changed = true
        if (changed) {
            annotationOverlayView.visibility = View.VISIBLE
            annotationOverlayView.invalidate()
        }
    }

    // Utility
    fun annotationColorToAndroidColor(color: AnnotationColor): Int {
        return when (color) {
            AnnotationColor.GREEN -> Color.parseColor("#4CAF50")
            AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
            AnnotationColor.RED -> Color.parseColor("#F44336")
            AnnotationColor.BLACK -> Color.BLACK
        }
    }

    private fun showFanMenu(center: PointF) {
        annotationOverlayView.setTempLinePoints(null)
        val shapeOptions = listOf(
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, shapeOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option) {
                if (option is FanMenuView.Option.Shape) {
                    annotationViewModel.setCurrentShape(option.shape)
                    showColorMenu(center, option.shape)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    fun showColorMenu(center: PointF, shape: PointShape) {
        val colorOptions = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, colorOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option) {
                if (option is FanMenuView.Option.Color) {
                    annotationViewModel.setCurrentColor(option.color)
                    addPoiFromFanMenu(shape, option.color)
                }
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize)
        fanMenuView.visibility = View.VISIBLE
    }

    fun addPoiFromFanMenu(shape: PointShape, color: AnnotationColor) {
        val latLng = pendingPoiLatLng ?: return
        annotationViewModel.setCurrentShape(shape)
        annotationViewModel.setCurrentColor(color)
        annotationViewModel.addPointOfInterest(LatLng(latLng.latitude, latLng.longitude))
        fanMenuView.visibility = View.GONE
        pendingPoiLatLng = null
        onAnnotationChanged?.invoke()
        annotationOverlayView.updateAnnotations(annotationViewModel.uiState.value.annotations)
        annotationOverlayView.invalidate()
    }

    fun setupLineToolButtons(
        lineToolConfirmButton: View,
        lineToolCancelButton: View,
        lineToolButtonFrame: View,
        lineToolLabel: View,
        lineToolButton: View
    ) {
        this.lineToolConfirmButton = lineToolConfirmButton
        this.lineToolCancelButton = lineToolCancelButton
        this.lineToolButtonFrame = lineToolButtonFrame
        this.lineToolLabel = lineToolLabel
        lineToolButton.setOnClickListener {
            isLineDrawingMode = true
            tempLinePoints.clear()
            annotationOverlayView.setTempLinePoints(null)
            lineToolButtonFrame.visibility = View.GONE
            lineToolLabel.visibility = View.GONE
            lineToolCancelButton.visibility = View.VISIBLE
            lineToolConfirmButton.visibility = View.VISIBLE
            updateLineToolConfirmState()
        }
        lineToolCancelButton.setOnClickListener {
            finishLineDrawing(cancel = true)
        }
        lineToolConfirmButton.setOnClickListener {
            finishLineDrawing(cancel = false)
        }
        lineToolCancelButton.visibility = View.GONE
        lineToolConfirmButton.visibility = View.GONE
    }

    private fun setAnnotationExpiration(annotationId: String) {
        val expirationTime = System.currentTimeMillis() + (3 * 60 * 1000) // 3 minutes from now
        annotationViewModel.setAnnotationExpiration(annotationId, expirationTime)
    }
} 