package com.tak.lite.ui.map

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlinx.coroutines.launch

class AnnotationController(
    private val fragment: Fragment,
    private val binding: ActivityMainBinding,
    private val annotationViewModel: AnnotationViewModel,
    private val meshNetworkViewModel: MeshNetworkViewModel,
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
                Log.d("AnnotationController", "onPoiLongPressed: poiId=$poiId, screenPosition=$screenPosition")
                editingPoiId = poiId
                showPoiEditMenu(screenPosition, poiId)
            }
            override fun onLineLongPressed(lineId: String, screenPosition: PointF) {
                Log.d("AnnotationController", "onLineLongPressed: lineId=$lineId, screenPosition=$screenPosition")
                showLineEditMenu(screenPosition, lineId)
            }
            override fun onPeerLongPressed(peerId: String, screenPosition: PointF) {
                Log.d("AnnotationController", "onPeerLongPressed: peerId=$peerId, screenPosition=$screenPosition")
                showPeerMenu(screenPosition, peerId)
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
        Log.d("AnnotationController", "showPoiEditMenu: center=$center, poiId=$poiId")
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
        Log.d("AnnotationController", "Calling fanMenuView.showAt for POI with options: $options at $center")
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Shape -> updatePoiShape(poi, option.shape)
                    is FanMenuView.Option.Color -> updatePoiColor(poiId, option.color)
                    is FanMenuView.Option.Timer -> setAnnotationExpiration(poiId)
                    is FanMenuView.Option.Delete -> deletePoi(option.id)
                    else -> {}
                }
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for POI")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, org.maplibre.android.geometry.LatLng(poi.position.lt, poi.position.lng))
        Log.d("AnnotationController", "Calling fanMenuView.bringToFront for POI")
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for POI")
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
        Log.d("AnnotationController", "showLineEditMenu: center=$center, lineId=$lineId")
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
            FanMenuView.Option.Delete(lineId),
            FanMenuView.Option.ElevationChart(lineId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        Log.d("AnnotationController", "Calling fanMenuView.showAt for LINE with options: $options at $center")
        val lineLatLng = line.points.firstOrNull()?.let { org.maplibre.android.geometry.LatLng(it.lt, it.lng) }
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.LineStyle -> updateLineStyle(line, option.style)
                    is FanMenuView.Option.Color -> updateLineColor(line, option.color)
                    is FanMenuView.Option.Timer -> setAnnotationExpiration(lineId)
                    is FanMenuView.Option.Delete -> deletePoi(option.id)
                    is FanMenuView.Option.ElevationChart -> showElevationChartBottomSheet(lineId)
                    else -> {}
                }
                annotationOverlayView.updateAnnotations(annotationViewModel.uiState.value.annotations)
                annotationOverlayView.setTempLinePoints(null)
                annotationOverlayView.invalidate()
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for LINE")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, lineLatLng)
        Log.d("AnnotationController", "Calling fanMenuView.bringToFront for LINE")
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for LINE")
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
            Toast.makeText(fragment.requireContext(), "Line added!", Toast.LENGTH_SHORT).show()
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
        Log.d("AnnotationController", "showFanMenu: center=$center")
        annotationOverlayView.setTempLinePoints(null)
        val shapeOptions = listOf(
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val menuLatLng = pendingPoiLatLng?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        fanMenuView.showAt(center, shapeOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                if (option is FanMenuView.Option.Shape) {
                    annotationViewModel.setCurrentShape(option.shape)
                    showColorMenu(center, option.shape)
                    return true
                }
                return false
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, menuLatLng)
        fanMenuView.bringToFront()
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
        val menuLatLng = pendingPoiLatLng?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        fanMenuView.showAt(center, colorOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                if (option is FanMenuView.Option.Color) {
                    annotationViewModel.setCurrentColor(option.color)
                    addPoiFromFanMenu(shape, option.color)
                }
                return false
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, menuLatLng)
        fanMenuView.bringToFront()
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

    fun showBulkFanMenu(center: PointF, onAction: (BulkEditAction) -> Unit) {
        val options = mutableListOf<FanMenuView.Option>()
        // Color options
        options.add(FanMenuView.Option.Color(AnnotationColor.GREEN))
        options.add(FanMenuView.Option.Color(AnnotationColor.YELLOW))
        options.add(FanMenuView.Option.Color(AnnotationColor.RED))
        options.add(FanMenuView.Option.Color(AnnotationColor.BLACK))
        // Expiration (timer) option
        options.add(FanMenuView.Option.Timer("bulk"))
        // Delete option
        options.add(FanMenuView.Option.Delete("bulk"))
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Color -> onAction(BulkEditAction.ChangeColor(option.color))
                    is FanMenuView.Option.Timer -> {
                        // Show expiration input dialog
                        val input = android.widget.EditText(fragment.requireContext())
                        input.inputType = android.text.InputType.TYPE_CLASS_NUMBER
                        input.hint = "Expiration in minutes"
                        androidx.appcompat.app.AlertDialog.Builder(fragment.requireContext())
                            .setTitle("Set Expiration (minutes)")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                val minutes = input.text.toString().toLongOrNull() ?: 0L
                                if (minutes > 0) {
                                    onAction(BulkEditAction.SetExpiration(minutes * 60 * 1000))
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    is FanMenuView.Option.Delete -> onAction(BulkEditAction.Delete)
                    else -> {}
                }
                return false // Dismiss after selection
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, null)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
    }

    // Stub for showing the elevation chart bottom sheet
    private fun showElevationChartBottomSheet(lineId: String) {
        android.util.Log.d("AnnotationController", "showElevationChartBottomSheet called for lineId=$lineId")
        val line = annotationViewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.Line>().find { it.id == lineId } ?: return
        val activity = fragment.requireActivity() as? androidx.fragment.app.FragmentActivity
        if (activity != null) {
            android.util.Log.d("AnnotationController", "Showing ElevationChartBottomSheet for lineId=$lineId")
            val sheet = ElevationChartBottomSheet(line)
            sheet.show(activity.supportFragmentManager, "ElevationChartBottomSheet")
        } else {
            android.util.Log.e("AnnotationController", "Fragment's activity is not a FragmentActivity, cannot show bottom sheet")
        }
    }

    fun showPeerMenu(center: PointF, peerId: String) {
        Log.d("AnnotationController", "showPeerMenu: center=$center, peerId=$peerId")
        val options = listOf(
            FanMenuView.Option.DirectMessage(peerId),
            FanMenuView.Option.LocationRequest(peerId),
            FanMenuView.Option.Info(peerId),
            FanMenuView.Option.DrawLine(peerId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        Log.d("AnnotationController", "Calling fanMenuView.showAt for PEER with options: $options at $center")
        val peerLatLng = meshNetworkViewModel.peerLocations.value[peerId]?.let { org.maplibre.android.geometry.LatLng(it.latitude, it.longitude) }
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.DirectMessage -> handleDirectMessage(option.id)
                    is FanMenuView.Option.LocationRequest -> handleLocationRequest(option.id)
                    is FanMenuView.Option.Info -> handleViewInfo(option.id)
                    is FanMenuView.Option.DrawLine -> handleDrawLineToPeer(option.id)
                    else -> {}
                }
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for PEER")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, peerLatLng)
        Log.d("AnnotationController", "Calling fanMenuView.bringToFront for PEER")
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for PEER")
    }

    private fun handleDirectMessage(peerId: String) {
        // TODO: Implement direct messaging
        Toast.makeText(fragment.requireContext(), "Coming soon! Direct message to $peerId", Toast.LENGTH_SHORT).show()
    }

    private fun handleLocationRequest(peerId: String) {
        // TODO: Implement location request
        Toast.makeText(fragment.requireContext(), "Coming soon! Requesting location from $peerId", Toast.LENGTH_SHORT).show()
    }

    private fun handleViewInfo(peerId: String) {
        // Launch a coroutine to handle the suspend function call
        (fragment as? androidx.fragment.app.Fragment)?.viewLifecycleOwner?.lifecycleScope?.launch {
            val nodeInfo = meshNetworkViewModel.getNodeInfo(peerId)
            annotationOverlayView.showPeerPopover(peerId, nodeInfo)
        }
    }

    private fun handleDrawLineToPeer(peerId: String) {
        val userLocation = meshNetworkViewModel.phoneLocation.value ?: meshNetworkViewModel.userLocation.value
        val peerLocation = meshNetworkViewModel.peerLocations.value[peerId]
        Log.d("AnnotationController", "handleDrawLineToPeer: peerId=$peerId")
        Log.d("AnnotationController", "handleDrawLineToPeer: phoneLocation=$userLocation")
        Log.d("AnnotationController", "handleDrawLineToPeer: peerLocation=$peerLocation")
        Log.d("AnnotationController", "handleDrawLineToPeer: all peer locations=${meshNetworkViewModel.peerLocations.value}")
        
        if (userLocation != null && peerLocation != null) {
            // Create a line from user location to peer
            val points = listOf(
                userLocation,
                peerLocation
            )
            Log.d("AnnotationController", "handleDrawLineToPeer: Creating line with points=$points")
            annotationViewModel.addLine(points)
            Toast.makeText(fragment.requireContext(), "Line drawn to $peerId", Toast.LENGTH_SHORT).show()
        } else {
            val errorMsg = when {
                userLocation == null -> "User location is not available"
                peerLocation == null -> "Peer location is not available"
                else -> "Unknown error"
            }
            Log.e("AnnotationController", "handleDrawLineToPeer: Cannot draw line: $errorMsg")
            Toast.makeText(fragment.requireContext(), "Cannot draw line: $errorMsg", Toast.LENGTH_SHORT).show()
        }
    }
}