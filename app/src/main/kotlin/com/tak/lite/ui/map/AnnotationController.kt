package com.tak.lite.ui.map

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.tak.lite.MessageActivity
import com.tak.lite.R
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.util.haversine
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class AnnotationController(
    private val fragment: Fragment,
    private val binding: ActivityMainBinding,
    private val annotationViewModel: AnnotationViewModel,
    private val meshNetworkViewModel: MeshNetworkViewModel,
    private val messageViewModel: MessageViewModel,
    private val fanMenuView: FanMenuView,
    private val annotationOverlayView: AnnotationOverlayView,
    private val onAnnotationChanged: (() -> Unit)? = null,
    mapLibreMap: MapLibreMap
) {
    private var pendingPoiLatLng: LatLng? = null
    var editingPoiId: String? = null
    var isLineDrawingMode: Boolean = false
    var tempLinePoints: MutableList<LatLng> = mutableListOf()
    private var shouldCreatePolygon: Boolean = false
    private var pendingPolygonTouchLatLng: LatLng? = null // Store touch location for polygon POI creation
    
    // Area drawing state
    var isAreaDrawingMode: Boolean = false
    private var tempAreaCenter: LatLng? = null
    private var tempAreaRadius: Double = 0.0
    private var tempAreaRadiusPixels: Float = 300f // Default 300 pixels
    private lateinit var lineToolConfirmButton: View
    private lateinit var lineToolCancelButton: View
    private lateinit var lineToolButtonFrame: View
    private lateinit var lineToolLabel: View
    private var lastOverlayProjection: org.maplibre.android.maps.Projection? = null
    private var lastOverlayAnnotations: List<MapAnnotation> = emptyList()
    private var lastPoiAnnotations: List<MapAnnotation.PointOfInterest> = emptyList()
    var mapController: MapController? = null
    
    // === NATIVE CLUSTERING SUPPORT ===
    private var clusteredLayerManager: ClusteredLayerManager
    var clusteringConfig: ClusteringConfig = ClusteringConfig.getDefault()
    private var lastPeerUpdate = 0L
    private val PEER_UPDATE_THROTTLE_MS = 100L
    
    // === HYBRID POPOVER SUPPORT ===
    var popoverManager: HybridPopoverManager
    private var lastPopoverUpdate = 0L
    private val POPOVER_UPDATE_THROTTLE_MS = 10L
    
    // === POI TIMER SUPPORT ===
    private var poiTimerManager: PoiTimerManager? = null
    
    // Getter for timer manager
    val timerManager: PoiTimerManager? get() = poiTimerManager
    
    // === LINE TIMER SUPPORT ===
    var lineTimerManager: LineTimerManager? = null
    
    // === LINE DISTANCE SUPPORT ===
    private var _lineDistanceManager: LineDistanceManager? = null
    
    // Getter for line distance manager
    val lineDistanceManager: LineDistanceManager? get() = _lineDistanceManager
    
    // === AREA TIMER SUPPORT ===
    private var _areaTimerManager: AreaTimerManager? = null
    
    // Getter for area timer manager
    val areaTimerManager: AreaTimerManager? get() = _areaTimerManager
    
    // === POLYGON TIMER SUPPORT ===
    private var _polygonTimerManager: PolygonTimerManager? = null
    
    // Getter for polygon timer manager
    val polygonTimerManager: PolygonTimerManager? get() = _polygonTimerManager

    // === DEVICE LOCATION MANAGER ===
    private var _deviceLocationManager: DeviceLocationLayerManager? = null
    // Getter for device location manager
    val deviceLocationManager: DeviceLocationLayerManager? get() = _deviceLocationManager

    // === CLUSTER TEXT MANAGER ===
    private var _clusterTextManager: ClusterTextManager? = null

    // Getter for cluster text manager
    val clusterTextManager: ClusterTextManager? get() = _clusterTextManager
    
    // === UNIFIED ANNOTATION MANAGER ===
    private var unifiedAnnotationManager: UnifiedAnnotationManager? = null

    companion object {
        private const val TAG = "AnnotationController"
    }

    init {
        clusteredLayerManager = ClusteredLayerManager(mapLibreMap, clusteringConfig)
        popoverManager = HybridPopoverManager(fragment.requireContext(), mapLibreMap, binding.root, meshNetworkViewModel)
        unifiedAnnotationManager = UnifiedAnnotationManager(mapLibreMap)
        _deviceLocationManager = DeviceLocationLayerManager(mapLibreMap)
        _clusterTextManager = ClusterTextManager(mapLibreMap)
    }
    
    // === ENHANCED: Convert peer locations to clustered GeoJSON FeatureCollection ===
    private fun peerLocationsToClusteredFeatureCollection(peerLocations: Map<String, com.tak.lite.model.PeerLocationEntry>): FeatureCollection {
        // Use user-configurable staleness threshold from SharedPreferences
        val context = fragment.requireContext()
        val prefs = context.getSharedPreferences("user_prefs", android.content.Context.MODE_PRIVATE)
        val stalenessThresholdMinutes = prefs.getInt("peer_staleness_threshold_minutes", 10)
        val stalenessThresholdMs = stalenessThresholdMinutes * 60 * 1000L

        val features = peerLocations.map { (peerId, entry) ->
            val point = Point.fromLngLat(entry.longitude, entry.latitude)
            val feature = Feature.fromGeometry(point, null, peerId)
            val userStatus = entry.userStatus?.name ?: "GREEN"
            val now = System.currentTimeMillis()
            val isStale = (now - entry.timestamp) > stalenessThresholdMs
            
                    // Calculate colors based on status and staleness
        val fillColor = if (isStale) {
            "#BDBDBD" // gray for stale
        } else {
            when (userStatus) {
                "RED" -> "#F44336"
                "YELLOW" -> "#FFC107"
                "BLUE" -> "#2196F3"
                "ORANGE" -> "#FF9800"
                "VIOLET" -> "#9C27B0"
                "GREEN" -> "#4CAF50"
                else -> "#4CAF50" // default green
            }
        }
        
        val borderColor = if (isStale) {
            when (userStatus) {
                "RED" -> "#F44336"
                "YELLOW" -> "#FFC107"
                "BLUE" -> "#2196F3"
                "ORANGE" -> "#FF9800"
                "VIOLET" -> "#9C27B0"
                "GREEN" -> "#4CAF50"
                else -> "#4CAF50" // default green
            }
        } else {
            "#FFFFFF" // white for fresh
        }
            
            feature.addStringProperty("peerId", peerId)
            feature.addStringProperty("userStatus", userStatus)
            feature.addBooleanProperty("isStale", isStale)
            feature.addStringProperty("fillColor", fillColor)
            feature.addStringProperty("borderColor", borderColor)

            feature
        }

        return FeatureCollection.fromFeatures(features)
    }

    // === ENHANCED: Convert POI annotations to clustered GeoJSON FeatureCollection ===
    private fun poiAnnotationsToClusteredFeatureCollection(pois: List<MapAnnotation.PointOfInterest>): FeatureCollection {
        val features = mutableListOf<Feature>()
        
        // Add POI features
        pois.forEach { poi ->
            val point = Point.fromLngLat(poi.position.lng, poi.position.lt)
            val feature = Feature.fromGeometry(point)
            
            // Create icon name based on shape and color
            val iconName = "poi-${poi.shape.name.lowercase()}-${poi.color.name.lowercase()}"
            
            feature.addStringProperty("poiId", poi.id)
            feature.addStringProperty("icon", iconName)
            feature.addStringProperty("label", poi.label ?: "")
            feature.addStringProperty("color", poi.color.name.lowercase())
            feature.addStringProperty("shape", poi.shape.name.lowercase())
            feature.addNumberProperty("timestamp", poi.timestamp)
            feature.addNumberProperty("expirationTime", poi.expirationTime ?: 0L)
            
            // Add timer-specific properties for POIs with expiration times
            if (poi.expirationTime != null) {
                val now = System.currentTimeMillis()
                val secondsRemaining = (poi.expirationTime - now) / 1000
                val isWarning = secondsRemaining <= 60 // 1 minute warning
                val isCritical = secondsRemaining <= 10 // 10 seconds critical
                
                feature.addBooleanProperty("hasTimer", true)
                feature.addNumberProperty("secondsRemaining", secondsRemaining)
                feature.addBooleanProperty("isWarning", isWarning)
                feature.addBooleanProperty("isCritical", isCritical)
                feature.addStringProperty("timerColor", if (isCritical) "#FF0000" else if (isWarning) "#FFA500" else poi.color.name.lowercase())
            } else {
                feature.addBooleanProperty("hasTimer", false)
            }
            
            Log.d("PoiDebug", "Creating POI feature: poiId=${poi.id}, icon=$iconName, label=${poi.label}, color=${poi.color}, shape=${poi.shape}, hasTimer=${poi.expirationTime != null}")
            features.add(feature)
        }
        
        // Add line endpoint features (invisible but participate in clustering)
        if (clusteringConfig.enableLineClustering) {
            val lineAnnotations = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Line>()
            lineAnnotations.forEach { line ->
                if (line.points.isNotEmpty()) {
                    // Add start point
                    val startPoint = Point.fromLngLat(line.points.first().lng, line.points.first().lt)
                    val startFeature = Feature.fromGeometry(startPoint)
                    startFeature.addStringProperty("lineId", line.id)
                    startFeature.addStringProperty("endpointType", "start")
                    startFeature.addStringProperty("label", "Line Start")
                    startFeature.addStringProperty("type", "line_endpoint")
                    
                    features.add(startFeature)
                    
                    // Add end point (if different from start)
                    if (line.points.size > 1) {
                        val endPoint = Point.fromLngLat(line.points.last().lng, line.points.last().lt)
                        val endFeature = Feature.fromGeometry(endPoint)
                        endFeature.addStringProperty("lineId", line.id)
                        endFeature.addStringProperty("endpointType", "end")
                        endFeature.addStringProperty("label", "Line End")
                        endFeature.addStringProperty("type", "line_endpoint")
                        
                        features.add(endFeature)
                    }
                }
            }
            Log.d("PoiDebug", "Added ${lineAnnotations.size * 2} line endpoint features to POI clustering")
        }
        
        return FeatureCollection.fromFeatures(features.toTypedArray())
    }

    // Generate POI icons for MapLibre
    private fun generatePoiIcons(style: org.maplibre.android.maps.Style) {
        Log.d("PoiDebug", "generatePoiIcons called")
        val shapes = listOf(PointShape.CIRCLE, PointShape.SQUARE, PointShape.TRIANGLE, PointShape.EXCLAMATION)
        val colors = listOf(AnnotationColor.GREEN, AnnotationColor.YELLOW, AnnotationColor.RED, AnnotationColor.BLACK)
        
        shapes.forEach { shape ->
            colors.forEach { color ->
                val iconName = "poi-${shape.name.lowercase()}-${color.name.lowercase()}"
                // Check if icon already exists
                if (style.getImage(iconName) == null) {
                    val bitmap = createPoiIconBitmap(shape, color)
                    style.addImage(iconName, bitmap)
                    Log.d("PoiDebug", "Generated icon: $iconName")
                }
            }
        }
    }

    // Create bitmap for POI icon
    private fun createPoiIconBitmap(shape: PointShape, color: AnnotationColor): android.graphics.Bitmap {
        val size = 80
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = annotationColorToAndroidColor(color)
            style = android.graphics.Paint.Style.FILL
        }
        
        val centerX = size / 2f
        val centerY = size / 2f
        val radius = size / 3f
        
        when (shape) {
            PointShape.CIRCLE -> {
                canvas.drawCircle(centerX, centerY, radius, paint)
            }
            PointShape.SQUARE -> {
                val rect = android.graphics.RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
                canvas.drawRect(rect, paint)
            }
            PointShape.TRIANGLE -> {
                val path = android.graphics.Path()
                val height = radius * 2
                path.moveTo(centerX, centerY - height / 2)
                path.lineTo(centerX - radius, centerY + height / 2)
                path.lineTo(centerX + radius, centerY + height / 2)
                path.close()
                canvas.drawPath(path, paint)
            }
            PointShape.EXCLAMATION -> {
                // Draw triangle with exclamation mark
                val path = android.graphics.Path()
                val height = radius * 2
                path.moveTo(centerX, centerY - height / 2)
                path.lineTo(centerX - radius, centerY + height / 2)
                path.lineTo(centerX + radius, centerY + height / 2)
                path.close()
                canvas.drawPath(path, paint)
                
                // Draw white exclamation mark
                val exMarkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4f
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }
                val exMarkTop = centerY - height / 6
                val exMarkBottom = centerY + height / 6
                canvas.drawLine(centerX, exMarkTop, centerX, exMarkBottom, exMarkPaint)
                
                // Draw dot
                val dotRadius = 3f
                val dotCenterY = exMarkBottom + dotRadius * 2f
                val dotPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = Color.WHITE
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawCircle(centerX, dotCenterY, dotRadius, dotPaint)
            }
        }
        
        return bitmap
    }

    // Initialize timer managers when map is ready
    fun initializeTimerManager(mapLibreMap: MapLibreMap?) {
        if (poiTimerManager == null && mapLibreMap != null) {
            poiTimerManager = PoiTimerManager(mapLibreMap, annotationViewModel)
            Log.d("AnnotationController", "POI timer manager initialized")
        }
        if (lineTimerManager == null && mapLibreMap != null) {
            lineTimerManager = LineTimerManager(mapLibreMap, annotationViewModel)
            Log.d("AnnotationController", "Line timer manager initialized")
        }
        if (_lineDistanceManager == null && mapLibreMap != null) {
            _lineDistanceManager = LineDistanceManager(fragment.requireContext())
            Log.d("AnnotationController", "Line distance manager initialized")
        }
        if (polygonTimerManager == null && mapLibreMap != null) {
            _polygonTimerManager = PolygonTimerManager(mapLibreMap, annotationViewModel)
            Log.d("AnnotationController", "Polygon timer manager initialized")
        }
        if (deviceLocationManager == null && mapLibreMap != null) {
            _deviceLocationManager = DeviceLocationLayerManager(mapLibreMap)
            Log.d("AnnotationController", "Device location manager initialized")
        }
        if (clusterTextManager == null && mapLibreMap != null) {
            _clusterTextManager = ClusterTextManager(mapLibreMap)
            Log.d("AnnotationController", "Cluster text manager initialized")
        }
        if (_areaTimerManager == null && mapLibreMap != null) {
            _areaTimerManager = AreaTimerManager(mapLibreMap, annotationViewModel)
            Log.d("AnnotationController", "Area timer manager initialized")
        }
        
        // Initialize timer managers if needed
        poiTimerManager?.setupTimerLayers()
        lineTimerManager?.setupTimerLayers()
        _areaTimerManager?.setupTimerLayers()
        _polygonTimerManager?.setupTimerLayers()
    }
    
    // Clean up all resources
    fun cleanup() {
        poiTimerManager?.cleanup()
        lineTimerManager?.cleanup()
        polygonTimerManager?.cleanup()
        unifiedAnnotationManager?.cleanup()
        deviceLocationManager?.cleanup()
        clusterTextManager?.cleanup()
        _areaTimerManager?.cleanup()
        _lineDistanceManager?.clearDistanceFeatures()
        Log.d("AnnotationController", "Annotation controller cleaned up")
    }
    // Overlay and menu setup
    fun setupAnnotationOverlay(mapLibreMap: MapLibreMap?) {
        mapLibreMap?.addOnCameraMoveListener {
            // IMMEDIATE: Only update projection for visual sync
            annotationOverlayView.setProjection(mapLibreMap.projection)
            
            // Notify cluster text manager about camera movement for performance optimization
            clusterTextManager?.onCameraMoving()
            
            // Throttled popover position updates for performance
            val now = System.currentTimeMillis()
            if (now - lastPopoverUpdate >= POPOVER_UPDATE_THROTTLE_MS) {
                popoverManager.updatePopoverPosition()
                lastPopoverUpdate = now
            }
            
            // Remove invalidate() call - it's handled by the fragment's throttled sync
        }
        annotationOverlayView.setProjection(mapLibreMap?.projection)
        annotationOverlayView.invalidate()

        // === BEGIN: Native Peer Dot Layer Setup ===
        mapLibreMap?.getStyle { style ->
            // Generate POI icons first
            generatePoiIcons(style)

            unifiedAnnotationManager?.cleanup()
            // Initialize unified annotation manager
            // Always reset the unified manager on style changes so line/area/polygon layers are re-created
            // because MapLibre destroys all layers/sources when a new style is applied.
            unifiedAnnotationManager?.setLineLayersReadyCallback(object : UnifiedAnnotationManager.LineLayersReadyCallback {
                override fun onLineLayersReady() {
                    Log.d(TAG, "Line layers ready, retrying line timer setup")
                    lineTimerManager?.retrySetupTimerLayers()
                }
            })
            unifiedAnnotationManager?.initialize()
            Log.d(TAG, "Unified annotation manager initialization requested")
            
            // Initialize timer managers and setup timer layers
            initializeTimerManager(mapLibreMap)
            poiTimerManager?.setupTimerLayers()
            lineTimerManager?.setupTimerLayers()
            polygonTimerManager?.setupTimerLayers()
            
            // Setup device location layers
            deviceLocationManager?.setupDeviceLocationLayers()
            
            // Only create non-clustered layers if clustering is disabled
            if (!clusteringConfig.enablePeerClustering) {
                // Add or update GeoJsonSource for peer dots
                val sourceId = "peer-dots-source"
                val layerId = "peer-dots-layer"
                if (style.getSource(sourceId) == null) {
                    val emptyCollection = FeatureCollection.fromFeatures(arrayOf())
                    val source = GeoJsonSource(sourceId, emptyCollection)
                    style.addSource(source)
                }
                if (style.getLayer(layerId) == null) {
                    val layer = CircleLayer(layerId, sourceId)
                    // Fill color: custom gray if stale, else status color
                    layer.withProperties(
                        PropertyFactory.circleColor(
                            Expression.get("fillColor")
                        ),
                        PropertyFactory.circleRadius(5f),
                        // Border color: status color if stale, else white
                        PropertyFactory.circleStrokeColor(
                            Expression.get("borderColor")
                        ),
                        PropertyFactory.circleStrokeWidth(3f)
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f))) // Only show peers at zoom 7+
                    style.addLayer(layer)

                    // Add invisible hit area layer for easier tapping (non-clustered)
                    val hitAreaLayer = CircleLayer("peer-dots-hit-area", sourceId)
                        .withProperties(
                            PropertyFactory.circleColor("#FFFFFF"), // Transparent
                            PropertyFactory.circleOpacity(0f),
                            PropertyFactory.circleRadius(20f), // Larger hit area
                        )
                        .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f))) // Only show hit area at zoom 7+
                    style.addLayer(hitAreaLayer)
                }
            }
            
            // Only create non-clustered POI layers if clustering is disabled
            if (!clusteringConfig.enablePoiClustering) {
                // Add or update GeoJsonSource for POI annotations
                val poiSourceId = "poi-source"
                val poiLayerId = "poi-layer"
                if (style.getSource(poiSourceId) == null) {
                    val emptyCollection = FeatureCollection.fromFeatures(arrayOf())
                    val source = GeoJsonSource(poiSourceId, emptyCollection)
                    style.addSource(source)
                    Log.d("PoiDebug", "Non-clustered POI source created")
                } else {
                    Log.d("PoiDebug", "Non-clustered POI source already exists")
                }
                if (style.getLayer(poiLayerId) == null) {
                    val layer = org.maplibre.android.style.layers.SymbolLayer(poiLayerId, poiSourceId)
                    layer.withProperties(
                        PropertyFactory.iconImage(Expression.get("icon")),
                        PropertyFactory.iconSize(1.0f),
                        PropertyFactory.iconAllowOverlap(true),
                        PropertyFactory.textField(Expression.get("label")),
                        PropertyFactory.textColor(Expression.color(Color.WHITE)),
                        PropertyFactory.textSize(12f),
                        PropertyFactory.textOffset(arrayOf(0f, -2f)),
                        PropertyFactory.textAllowOverlap(true),
                        PropertyFactory.textIgnorePlacement(false)
                    )
                    .withFilter(Expression.gte(Expression.zoom(), Expression.literal(7f))) // Only show POIs at zoom 7+
                    style.addLayer(layer)
                    Log.d("PoiDebug", "Non-clustered POI layer created with min zoom filter")
                    
                    // Retry timer layer setup now that POI layer exists
                    poiTimerManager?.retrySetupTimerLayers()
                    lineTimerManager?.retrySetupTimerLayers()
                    polygonTimerManager?.retrySetupTimerLayers()
                } else {
                    Log.d("PoiDebug", "Non-clustered POI layer already exists")
                }
            }
            
            // Line endpoints are now included in POI clustering, no separate setup needed
        }
        // === END: Native Peer Dot Layer Setup ===

        // Observe the connected node ID
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            meshNetworkViewModel.selfId.collect { nodeId ->
                annotationOverlayView.setConnectedNodeId(nodeId)
            }
        }
    }

    // === ENHANCED: Update peer dots with native clustering ===
    fun updatePeerDotsOnMap(mapLibreMap: MapLibreMap?, peerLocations: Map<String, com.tak.lite.model.PeerLocationEntry>) {
        // Throttle updates for performance
        val now = System.currentTimeMillis()
        if (now - lastPeerUpdate < PEER_UPDATE_THROTTLE_MS) return
        lastPeerUpdate = now
        
        if (clusteringConfig.enablePeerClustering) {
            mapLibreMap?.getStyle { style ->
                val featureCollection = peerLocationsToClusteredFeatureCollection(peerLocations)

                // Debug: Log the GeoJSON string
                val geoJsonString = featureCollection.toJson()

                // Check if source already exists
                val existingSource = style.getSourceAs<GeoJsonSource>(ClusteredLayerManager.PEER_CLUSTERED_SOURCE)
                if (existingSource != null) {
                    // Update existing source
                    existingSource.setGeoJson(featureCollection)
                } else {
                    // Create new source with data and clustering options
                    try {
                        clusteredLayerManager.setupPeerClusteredLayer(geoJsonString)
                    } catch (e: Exception) {
                        Log.e("PeerDotDebug", "Failed to create clustered source: ${e.message}", e)
                    }
                }
            }
        } else {
            mapLibreMap?.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>("peer-dots-source")
                if (source != null) {
                    val featureCollection = peerLocationsToClusteredFeatureCollection(peerLocations)
                    source.setGeoJson(featureCollection)
                } else {
                    Log.e("PeerDotDebug", "Regular peer dots source not found")
                }
            }
        }
    }

    // === DEVICE LOCATION UPDATE ===
    fun updateDeviceLocation(location: LatLng?, stale: Boolean) {
        deviceLocationManager?.updateDeviceLocation(location, stale)
    }
    
    // === CLUSTER TEXT OVERLAY SETUP ===
    fun setClusterTextOverlayView(overlayView: ClusterTextOverlayView?) {
        clusterTextManager?.setClusterTextOverlayView(overlayView)
    }
    
    // === ENHANCED: Update POI annotations with native clustering ===
    fun updatePoiAnnotationsOnMap(mapLibreMap: MapLibreMap?, pois: List<MapAnnotation.PointOfInterest>) {
        Log.d("PoiDebug", "updatePoiAnnotationsOnMap called with ${pois.size} POIs, using native clustering")
        
        if (!clusteringConfig.enablePoiClustering) {
            Log.d("PoiDebug", "Native POI clustering disabled")
            return
        }
        
        mapLibreMap?.getStyle { style ->
            var featureCollection = FeatureCollection.fromFeatures(arrayOf())
            if (pois.isNotEmpty()) {
                featureCollection = poiAnnotationsToClusteredFeatureCollection(pois)
            }
            val source = style.getSourceAs<GeoJsonSource>(ClusteredLayerManager.POI_CLUSTERED_SOURCE)
            if (source != null) {
                source.setGeoJson(featureCollection.toJson())
            } else {
                // Create new source with data and clustering options
                Log.d("PoiDebug", "Creating new POI clustered source")
                try {
                    clusteredLayerManager.setupPoiClusteredLayer(featureCollection.toJson())
                } catch (e: Exception) {
                    Log.e("PeerDotDebug", "Failed to create clustered source: ${e.message}", e)
                }
                
                // Retry timer layer setup now that clustered POI layer exists
                poiTimerManager?.retrySetupTimerLayers()
                lineTimerManager?.retrySetupTimerLayers()
                polygonTimerManager?.retrySetupTimerLayers()
            }
        }
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
    
    fun handleMapLongPress(latLng: LatLng, mapLibreMap: MapLibreMap): Boolean {
        val projection = mapLibreMap.projection
        val screenPoint = projection.toScreenLocation(latLng)
        
        // First check if we're long pressing on the device location dot
        val deviceLocationFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, DeviceLocationLayerManager.DEVICE_LOCATION_FILL_LAYER)
        val deviceLocationFeature = deviceLocationFeatures.firstOrNull { it.getStringProperty("type") == "device_location" }
        if (deviceLocationFeature != null) {
            // Handle device location dot tap
            Log.d("AnnotationController", "Device location dot tapped")
            // TODO: Add device location specific actions if needed
            return true
        }
        
        // Then check if we're long pressing on a peer dot (including hit area layers)
        val peerFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, ClusteredLayerManager.PEER_DOTS_LAYER)
        val peerHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, ClusteredLayerManager.PEER_HIT_AREA_LAYER)
        val peerFallbackFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, "peer-dots-fallback")
        val peerNonClusteredHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, "peer-dots-hit-area")
        
        val peerFeature = peerFeatures.firstOrNull { it.getStringProperty("peerId") != null }
            ?: peerHitAreaFeatures.firstOrNull { it.getStringProperty("peerId") != null }
            ?: peerFallbackFeatures.firstOrNull { it.getStringProperty("peerId") != null }
            ?: peerNonClusteredHitAreaFeatures.firstOrNull { it.getStringProperty("peerId") != null }
        
        if (peerFeature != null) {
            val peerId = peerFeature.getStringProperty("peerId")
            showPeerMenu(screenPoint, peerId)
            return true
        }
        
        // Then check if we're long pressing on a POI (including fallback layer)
        val poiLayerId = if (clusteringConfig.enablePoiClustering) {
            ClusteredLayerManager.POI_DOTS_LAYER
        } else {
            "poi-layer"
        }
        val poiFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, poiLayerId)
        val poiFallbackFeatures = if (clusteringConfig.enablePoiClustering) {
            mapLibreMap.queryRenderedFeatures(screenPoint, "poi-symbols-fallback")
        } else {
            emptyList()
        }
        val poiFeature = poiFeatures.firstOrNull { it.getStringProperty("poiId") != null }
            ?: poiFallbackFeatures.firstOrNull { it.getStringProperty("poiId") != null }
        val lineEndpointFeature = poiFeatures.firstOrNull { it.getStringProperty("lineId") != null }
        
        if (poiFeature != null) {
            val poiId = poiFeature.getStringProperty("poiId")
            showPoiEditMenu(screenPoint, poiId)
            return true
        } else if (lineEndpointFeature != null) {
            val lineId = lineEndpointFeature.getStringProperty("lineId")
            showLineEditMenu(screenPoint, lineId)
            return true
        }
        
        // Check if we're long pressing on a line (including hit area layer for easier detection)
        val lineFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, LineLayerManager.LINE_LAYER)
        val lineHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, LineLayerManager.LINE_HIT_AREA_LAYER)
        val lineFeature = lineFeatures.firstOrNull { it.getStringProperty("lineId") != null }
            ?: lineHitAreaFeatures.firstOrNull { it.getStringProperty("lineId") != null }
        if (lineFeature != null) {
            val lineId = lineFeature.getStringProperty("lineId")
            showLineEditMenu(screenPoint, lineId)
            return true
        }
        
        // Line endpoints are now part of POI clusters, handled by POI long press detection
        
        // Check if we're long pressing on an area
        val areaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, AreaLayerManager.AREA_FILL_LAYER)
        val areaHitAreaFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, AreaLayerManager.AREA_HIT_AREA_LAYER)
        val areaFeature = areaFeatures.firstOrNull { it.getStringProperty("areaId") != null }
            ?: areaHitAreaFeatures.firstOrNull { it.getStringProperty("areaId") != null }
        if (areaFeature != null) {
            val areaId = areaFeature.getStringProperty("areaId")
            pendingPoiLatLng = latLng // Store the touch location for POI creation within area
            Log.d("AnnotationController", "Area long press detected: areaId=$areaId, touchLatLng=$latLng")
            showAreaMenu(screenPoint, areaId)
            return true
        }
        
        // Check if we're long pressing on a polygon
        val polygonFeatures = mapLibreMap.queryRenderedFeatures(screenPoint, PolygonLayerManager.POLYGON_HIT_AREA_LAYER)
        val polygonFeature = polygonFeatures.firstOrNull { it.getStringProperty("polygonId") != null }
        if (polygonFeature != null) {
            val polygonId = polygonFeature.getStringProperty("polygonId")
            Log.d("AnnotationController", "Polygon long press detected: $polygonId")
            pendingPolygonTouchLatLng = latLng // Store the touch location
            showPolygonFanMenu(screenPoint, polygonId)
            return true
        }
        
        // If not on any annotation, create a new POI
        val center = PointF(screenPoint.x, screenPoint.y)
        pendingPoiLatLng = latLng
        showFanMenu(center)
        return true
    }
    
    fun findPoisInLassoArea(lassoPoints: List<PointF>?, mapLibreMap: MapLibreMap): List<MapAnnotation.PointOfInterest> {
        if (lassoPoints == null || lassoPoints.size < 3) return emptyList()
        
        // Create a path from the lasso points
        val path = android.graphics.Path()
        path.moveTo(lassoPoints[0].x, lassoPoints[0].y)
        for (pt in lassoPoints.drop(1)) path.lineTo(pt.x, pt.y)
        path.close()
        
        // Get the bounds of the lasso path
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        
        // Query POIs within the lasso bounds using the correct layer IDs (including fallback)
        val poiLayerId = if (clusteringConfig.enablePoiClustering) {
            ClusteredLayerManager.POI_DOTS_LAYER
        } else {
            "poi-layer"
        }
        val poiFeatures = mapLibreMap.queryRenderedFeatures(bounds, poiLayerId)
        val poiFallbackFeatures = if (clusteringConfig.enablePoiClustering) {
            mapLibreMap.queryRenderedFeatures(bounds, "poi-symbols-fallback")
        } else {
            emptyList()
        }
        val allPoiFeatures = poiFeatures + poiFallbackFeatures
        
        val selectedPois = mutableListOf<MapAnnotation.PointOfInterest>()
        
        // Check each POI to see if it's inside the lasso path
        for (feature in allPoiFeatures) {
            val poiId = feature.getStringProperty("poiId") ?: continue
            val lat = feature.geometry()?.let { 
                if (it is org.maplibre.geojson.Point) it.coordinates()[1] else null 
            } ?: continue
            val lng = feature.geometry()?.let { 
                if (it is org.maplibre.geojson.Point) it.coordinates()[0] else null 
            } ?: continue
            
            val screenPt = mapLibreMap.projection.toScreenLocation(LatLng(lat, lng))
            val pointF = PointF(screenPt.x, screenPt.y)
            val contains = android.graphics.Region().apply {
                val pathBounds = android.graphics.RectF()
                path.computeBounds(pathBounds, true)
                setPath(path, android.graphics.Region(pathBounds.left.toInt(), pathBounds.top.toInt(), pathBounds.right.toInt(), pathBounds.bottom.toInt()))
            }.contains(pointF.x.toInt(), pointF.y.toInt())
            
            if (contains) {
                // Find the corresponding POI annotation from the ViewModel
                val poiAnnotation = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.PointOfInterest>().find { it.id == poiId }
                if (poiAnnotation != null) {
                    selectedPois.add(poiAnnotation)
                }
            }
        }
        
        return selectedPois
    }
    
    fun findLinesInLassoArea(lassoPoints: List<PointF>?, mapLibreMap: MapLibreMap): List<MapAnnotation.Line> {
        if (lassoPoints == null || lassoPoints.size < 3) return emptyList()
        
        // Create a path from the lasso points
        val path = android.graphics.Path()
        path.moveTo(lassoPoints[0].x, lassoPoints[0].y)
        for (pt in lassoPoints.drop(1)) path.lineTo(pt.x, pt.y)
        path.close()
        
        // Get the bounds of the lasso path
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        
        // Query lines within the lasso bounds (including hit area layer for better detection)
        val lineFeatures = mapLibreMap.queryRenderedFeatures(bounds, LineLayerManager.LINE_LAYER)
        val lineHitAreaFeatures = mapLibreMap.queryRenderedFeatures(bounds, LineLayerManager.LINE_HIT_AREA_LAYER)
        val allLineFeatures = lineFeatures + lineHitAreaFeatures
        
        val selectedLines = mutableListOf<MapAnnotation.Line>()
        
        // Check each line to see if any of its points are inside the lasso path
        for (feature in allLineFeatures) {
            val lineId = feature.getStringProperty("lineId") ?: continue
            
            // Find the corresponding line annotation from the ViewModel
            val lineAnnotation = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Line>().find { it.id == lineId }
            if (lineAnnotation != null) {
                // Check if any point of the line is inside the lasso path
                val hasPointInLasso = lineAnnotation.points.any { point ->
                    val screenPt = mapLibreMap.projection.toScreenLocation(point.toMapLibreLatLng())
                    val pointF = PointF(screenPt.x, screenPt.y)
                    android.graphics.Region().apply {
                        val pathBounds = android.graphics.RectF()
                        path.computeBounds(pathBounds, true)
                        setPath(path, android.graphics.Region(pathBounds.left.toInt(), pathBounds.top.toInt(), pathBounds.right.toInt(), pathBounds.bottom.toInt()))
                    }.contains(pointF.x.toInt(), pointF.y.toInt())
                }
                
                if (hasPointInLasso) {
                    selectedLines.add(lineAnnotation)
                }
            }
        }
        
        return selectedLines
    }
    
    fun findAreasInLassoArea(lassoPoints: List<PointF>?, mapLibreMap: MapLibreMap): List<MapAnnotation.Area> {
        if (lassoPoints == null || lassoPoints.size < 3) return emptyList()
        
        // Create a path from the lasso points
        val path = android.graphics.Path()
        path.moveTo(lassoPoints[0].x, lassoPoints[0].y)
        for (pt in lassoPoints.drop(1)) path.lineTo(pt.x, pt.y)
        path.close()
        
        // Get the bounds of the lasso path
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        
        // Query areas within the lasso bounds
        val areaFeatures = mapLibreMap.queryRenderedFeatures(bounds, AreaLayerManager.AREA_FILL_LAYER)
        
        val selectedAreas = mutableListOf<MapAnnotation.Area>()
        
        // Check each area to see if its center is inside the lasso path
        for (feature in areaFeatures) {
            val areaId = feature.getStringProperty("areaId") ?: continue
            
            // Find the corresponding area annotation from the ViewModel
            val areaAnnotation = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Area>().find { it.id == areaId }
            if (areaAnnotation != null) {
                // Check if the center of the area is inside the lasso path
                val screenPt = mapLibreMap.projection.toScreenLocation(areaAnnotation.center.toMapLibreLatLng())
                val pointF = PointF(screenPt.x, screenPt.y)
                val contains = android.graphics.Region().apply {
                    val pathBounds = android.graphics.RectF()
                    path.computeBounds(pathBounds, true)
                    setPath(path, android.graphics.Region(pathBounds.left.toInt(), pathBounds.top.toInt(), pathBounds.right.toInt(), pathBounds.bottom.toInt()))
                }.contains(pointF.x.toInt(), pointF.y.toInt())
                
                if (contains) {
                    selectedAreas.add(areaAnnotation)
                }
            }
        }
        
        return selectedAreas
    }

    // POI/line/area editing
    private fun getPoiById(poiId: String): MapAnnotation.PointOfInterest? {
        return annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.PointOfInterest>().find { it.id == poiId }
    }

    fun showPoiLabel(poiId: String, screenPosition: PointF) {
        Log.d("AnnotationController", "showPoiLabel: poiId=$poiId, screenPosition=$screenPosition")
        // Find the POI annotation to get its details
        val poi = getPoiById(poiId)
        if (poi != null) {
            Log.d("AnnotationController", "Found POI: ${poi.label}, position: ${poi.position}")
            popoverManager.showPoiPopover(poiId, poi)
            Log.d("AnnotationController", "showPoiLabel: called popoverManager.showPoiPopover")
        } else {
            Log.e("AnnotationController", "POI not found: $poiId")
        }
    }
    
    fun showPeerPopover(peerId: String) {
        Log.d("AnnotationController", "showPeerPopover: peerId=$peerId")
        
        // Get peer information from the mesh network
        val peerName = meshNetworkViewModel.getPeerName(peerId)
        val peerLastHeard = meshNetworkViewModel.getPeerLastHeard(peerId)
        val peerLocation = meshNetworkViewModel.peerLocations.value[peerId]
        
        Log.d("AnnotationController", "showPeerPopover: peerName=$peerName, peerLastHeard=$peerLastHeard, peerLocation=$peerLocation")
        
        if (peerLocation != null) {
            popoverManager.showPeerPopover(peerId, peerName, peerLastHeard, peerLocation.toLatLng())
            Log.d("AnnotationController", "showPeerPopover: called popoverManager.showPeerPopover with name=$peerName, lastHeard=$peerLastHeard")
        } else {
            Log.e("AnnotationController", "showPeerPopover: peer location not found for $peerId")
            Log.d("AnnotationController", "showPeerPopover: available peer locations: ${meshNetworkViewModel.peerLocations.value.keys}")
        }
    }

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
            FanMenuView.Option.Label(poiId),
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
                    is FanMenuView.Option.Label -> showLabelEditDialog(poiId, poi.label)
                    is FanMenuView.Option.Delete -> deletePoi(option.id)
                    else -> {}
                }
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for POI")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, LatLng(poi.position.lt, poi.position.lng))
        Log.d("AnnotationController", "Calling fanMenuView.bringToFront for POI")
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for POI")
    }

    fun updatePoiShape(poi: MapAnnotation.PointOfInterest, shape: PointShape) {
        annotationViewModel.updatePointOfInterest(poi.id, newShape = shape)
        // Force map redraw to update POI layer with new icon
        onAnnotationChanged?.invoke()
    }

    fun updatePoiColor(poiId: String, color: AnnotationColor) {
        annotationViewModel.updatePointOfInterest(poiId, newColor = color)
        // Force map redraw to update POI layer with new icon
        onAnnotationChanged?.invoke()
    }

    fun deletePoi(poiId: String) {
        Log.d("AnnotationController", "deletePoi: $poiId")
        annotationViewModel.removeAnnotation(poiId)
        // Force map redraw to update POI layer
        onAnnotationChanged?.invoke()
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
        val lineLatLng = line.points.firstOrNull()?.let { LatLng(it.lt, it.lng) }
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
                val nonPoiAnnotations = annotationViewModel.uiState.value.annotations.filterNot { it is MapAnnotation.PointOfInterest }
                val linesAndAreas = nonPoiAnnotations.filter { it is MapAnnotation.Line || it is MapAnnotation.Area || it is MapAnnotation.Polygon }
                Log.d("AnnotationController", "Updating unified annotation manager with ${linesAndAreas.size} lines/areas/polygons")
                unifiedAnnotationManager?.updateAnnotations(linesAndAreas)
                annotationOverlayView.updateAnnotations(nonPoiAnnotations)
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

    fun finishLineDrawing(cancel: Boolean) {
        if (!cancel && tempLinePoints.size >= 2) {
            if (shouldCreatePolygon && tempLinePoints.size >= 3) {
                // Create as polygon
                Log.d("PolygonDebug", "Creating polygon with ${tempLinePoints.size} points")
                annotationViewModel.addPolygon(tempLinePoints.toList())
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.polygon_created), Toast.LENGTH_SHORT).show()
            } else {
                // Create as line
                Log.d("PolygonDebug", "Creating line with ${tempLinePoints.size} points")
                annotationViewModel.addLine(tempLinePoints.toList())
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.line_added), Toast.LENGTH_SHORT).show()
            }
        }
        isLineDrawingMode = false
        shouldCreatePolygon = false
        tempLinePoints.clear()
        annotationOverlayView.setTempLinePoints(null)
        lineToolButtonFrame.visibility = View.VISIBLE
        lineToolLabel.visibility = View.VISIBLE
        lineToolCancelButton.visibility = View.GONE
        lineToolConfirmButton.visibility = View.GONE
        // Restore layers button container position when exiting line mode
        try {
            val activity = fragment.activity as? android.app.Activity
            val container = activity?.findViewById<android.widget.LinearLayout>(R.id.layersButtonContainer)
            container?.animate()?.translationY(0f)?.setDuration(150)?.start()
        } catch (_: Exception) { }
    }
    
    fun setShouldCreatePolygon(shouldCreate: Boolean) {
        Log.d("PolygonDebug", "Setting shouldCreatePolygon to $shouldCreate")
        shouldCreatePolygon = shouldCreate
    }

    // Rendering overlays
    fun renderAllAnnotations(mapLibreMap: MapLibreMap?) {
        // Update the annotation overlay with current annotations (excluding POIs)
        val state = annotationViewModel.uiState.value
        val allAnnotations = state.annotations
        val nonPoiAnnotations = allAnnotations.filterNot { it is MapAnnotation.PointOfInterest }
        val poiAnnotations = allAnnotations.filterIsInstance<MapAnnotation.PointOfInterest>()
        val lineAnnotations = allAnnotations.filterIsInstance<MapAnnotation.Line>()
        
        Log.d("PoiDebug", "renderAllAnnotations: total=${allAnnotations.size}, nonPoi=${nonPoiAnnotations.size}, poi=${poiAnnotations.size}, lines=${lineAnnotations.size}")
        Log.d("PoiDebug", "POI annotations: ${poiAnnotations.map { "${it.id}:${it.label}" }}")
        
        // Update unified annotation manager for lines, areas, and polygons
        val linesAndAreas = nonPoiAnnotations.filter { it is MapAnnotation.Line || it is MapAnnotation.Area || it is MapAnnotation.Polygon }
        unifiedAnnotationManager?.updateAnnotations(linesAndAreas)
        Log.d(TAG, "Updated unified annotation manager with ${linesAndAreas.size} lines/areas/polygons")
        
        // Update distance features for lines
        _lineDistanceManager?.updateDistanceFeatures(lineAnnotations)
        Log.d(TAG, "Updated distance features for ${lineAnnotations.size} lines")
        
        // Line endpoints are now included in POI clustering, no separate update needed
        
        // Update overlay view for remaining annotations (temporary lines, device location, etc.)
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
        annotationOverlayView.invalidate()
        
        // Update native POI annotations
        updatePoiAnnotationsOnMap(mapLibreMap, poiAnnotations)
    }

    fun syncAnnotationOverlayView(mapLibreMap: MapLibreMap?) {
        val currentProjection = mapLibreMap?.projection
        val currentAnnotations = annotationViewModel.uiState.value.annotations
        if (currentProjection != lastOverlayProjection) {
            annotationOverlayView.setProjection(currentProjection)
            lastOverlayProjection = currentProjection
        }
        
        // Update overlay with non-POI annotations
        val nonPoiAnnotations = currentAnnotations.filterNot { it is MapAnnotation.PointOfInterest }
        val poiAnnotations = currentAnnotations.filterIsInstance<MapAnnotation.PointOfInterest>()
        
        // Only update POI annotations if they've actually changed
        val poiAnnotationsChanged = poiAnnotations != lastPoiAnnotations
        if (poiAnnotationsChanged) {
            Log.d("PoiDebug", "syncAnnotationOverlayView: POI annotations changed, updating map")
            lastPoiAnnotations = poiAnnotations
            updatePoiAnnotationsOnMap(mapLibreMap, poiAnnotations)
        }
        
        // Only update unified annotations if they've actually changed
        val linesAndAreas = nonPoiAnnotations.filter { it is MapAnnotation.Line || it is MapAnnotation.Area || it is MapAnnotation.Polygon }
        val lastLinesAndAreas = lastOverlayAnnotations.filter { it is MapAnnotation.Line || it is MapAnnotation.Area || it is MapAnnotation.Polygon }
        val linesAndAreasChanged = linesAndAreas != lastLinesAndAreas
        if (linesAndAreasChanged) {
            Log.d("PoiDebug", "syncAnnotationOverlayView: lines/areas changed, updating unified manager")
            unifiedAnnotationManager?.updateAnnotations(linesAndAreas)
            lastOverlayAnnotations = nonPoiAnnotations
            
            // Update distance features for lines
            val lineAnnotations = nonPoiAnnotations.filterIsInstance<MapAnnotation.Line>()
            _lineDistanceManager?.updateDistanceFeatures(lineAnnotations)
            Log.d(TAG, "Updated distance features for ${lineAnnotations.size} lines")
            
            // Line endpoints are now included in POI clustering, no separate update needed
        }
        
        Log.d("PoiDebug", "syncAnnotationOverlayView: total=${currentAnnotations.size}, nonPoi=${nonPoiAnnotations.size}, poi=${poiAnnotations.size}, poiChanged=$poiAnnotationsChanged, linesAndAreasChanged=$linesAndAreasChanged")
        
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
    }

    // Utility
    private fun annotationColorToAndroidColor(color: AnnotationColor): Int {
        return when (color) {
            AnnotationColor.GREEN -> Color.parseColor("#4CAF50")
            AnnotationColor.YELLOW -> Color.parseColor("#FBC02D")
            AnnotationColor.RED -> Color.parseColor("#F44336")
            AnnotationColor.BLACK -> Color.BLACK
            AnnotationColor.WHITE -> Color.WHITE
        }
    }

    private fun showFanMenu(center: PointF) {
        Log.d("AnnotationController", "showFanMenu: center=$center")
        annotationOverlayView.setTempLinePoints(null)
        val shapeOptions = listOf(
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE),
            FanMenuView.Option.Area() // Add area option
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val menuLatLng = pendingPoiLatLng?.let { LatLng(it.latitude, it.longitude) }
        fanMenuView.showAt(center, shapeOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Shape -> {
                        annotationViewModel.setCurrentShape(option.shape)
                        showColorMenu(center, option.shape)
                        return true
                    }
                    is FanMenuView.Option.Area -> {
                        startAreaDrawing(center)
                        return false
                    }
                    else -> {}
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
        val menuLatLng = pendingPoiLatLng?.let { LatLng(it.latitude, it.longitude) }
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
        val nonPoiAnnotations = annotationViewModel.uiState.value.annotations.filterNot { it is MapAnnotation.PointOfInterest }
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
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
            // Move layers button container up to avoid overlap with confirm/cancel FABs
            try {
                val activity = fragment.activity as? android.app.Activity
                val container = activity?.findViewById<android.widget.LinearLayout>(R.id.layersButtonContainer)
                val density = fragment.resources.displayMetrics.density
                container?.animate()?.translationY((-60f * density))?.setDuration(150)?.start()
            } catch (_: Exception) { }
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
                        val input = android.widget.EditText(fragment.requireContext()).apply {
                            inputType = android.text.InputType.TYPE_CLASS_NUMBER
                            hint = "Expiration in minutes"
                        }
                        
                        // Helper function to handle expiration submission
                        fun submitExpiration() {
                            val minutes = input.text.toString().toLongOrNull() ?: 0L
                            if (minutes > 0) {
                                onAction(BulkEditAction.SetExpiration(minutes * 60 * 1000))
                            }
                        }
                        
                        // Add editor action listener to handle Enter key
                        input.setOnEditorActionListener { _, actionId, _ ->
                            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                                submitExpiration()
                                true // Consume the event
                            } else {
                                false // Don't consume other events
                            }
                        }
                        
                        val dialog = androidx.appcompat.app.AlertDialog.Builder(fragment.requireContext())
                            .setTitle(fragment.getString(R.string.set_expiration_minutes))
                            .setView(input)
                            .setPositiveButton(fragment.getString(R.string.ok)) { _, _ ->
                                submitExpiration()
                            }
                            .setNegativeButton(fragment.getString(R.string.cancel), null)
                            .create()
                        
                        // Show the dialog and request focus for the EditText
                        dialog.show()
                        input.requestFocus()
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
        Log.d("AnnotationController", "showElevationChartBottomSheet called for lineId=$lineId")
        val line = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Line>().find { it.id == lineId } ?: return
        val activity = fragment.requireActivity() as? androidx.fragment.app.FragmentActivity
        if (activity != null) {
            Log.d("AnnotationController", "Showing ElevationChartBottomSheet for lineId=$lineId")
            val sheet = ElevationChartBottomSheet(line)
            sheet.show(activity.supportFragmentManager, "ElevationChartBottomSheet")
        } else {
            Log.e("AnnotationController", "Fragment's activity is not a FragmentActivity, cannot show bottom sheet")
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
        val peerLatLng = meshNetworkViewModel.peerLocations.value[peerId]?.let { LatLng(it.latitude, it.longitude) }
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
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            try {
                val peerName = meshNetworkViewModel.getPeerName(peerId)
                
                // Create or get the direct message channel
                val channel = messageViewModel.getOrCreateDirectMessageChannel(peerId)

                if (channel != null) {
                    // Launch the MessageActivity using the companion object method
                    fragment.requireContext().startActivity(MessageActivity.createIntent(fragment.requireContext(), channel.id))
                }
            } catch (e: Exception) {
                Log.e("AnnotationController", "Error handling direct message: ${e.message}", e)
                Toast.makeText(fragment.requireContext(), fragment.getString(R.string.failed_to_start_direct_message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleLocationRequest(peerId: String) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val peerName = meshNetworkViewModel.getPeerName(peerId)
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.waiting_for_location_update, peerName), Toast.LENGTH_LONG).show()
            meshNetworkViewModel.requestPeerLocation(peerId, onLocationReceived = { timeout ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    if (timeout) {
                        Toast.makeText(fragment.requireContext(), fragment.getString(R.string.peer_did_not_respond, peerName), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(fragment.requireContext(), fragment.getString(R.string.received_updated_location, peerName), Toast.LENGTH_LONG).show()
                    }
                }
            })
        }
    }

    private fun handleViewInfo(peerId: String) {
        // Use the new hybrid popover manager
        showPeerPopover(peerId)
    }

    private fun handleDrawLineToPeer(peerId: String) {
        val userLocation = meshNetworkViewModel.bestLocation.value
        val peerLocation = meshNetworkViewModel.peerLocations.value[peerId]
        Log.d("AnnotationController", "handleDrawLineToPeer: peerId=$peerId")
        Log.d("AnnotationController", "handleDrawLineToPeer: bestLocation=$userLocation")
        Log.d("AnnotationController", "handleDrawLineToPeer: peerLocation=$peerLocation")
        Log.d("AnnotationController", "handleDrawLineToPeer: all peer locations=${meshNetworkViewModel.peerLocations.value}")
        
        if (userLocation != null && peerLocation != null) {
            // Create a line from user location to peer
            val points = listOf(
                userLocation,
                peerLocation.toLatLng()
            )
            Log.d("AnnotationController", "handleDrawLineToPeer: Creating line with points=$points")
            annotationViewModel.addLine(points)
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.line_drawn_to_peer, peerId), Toast.LENGTH_SHORT).show()
        } else {
            val errorMsg = when {
                userLocation == null -> "User location is not available"
                peerLocation == null -> "Peer location is not available"
                else -> "Unknown error"
            }
            Log.e("AnnotationController", "handleDrawLineToPeer: Cannot draw line: $errorMsg")
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.cannot_draw_line, errorMsg), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showLabelEditDialog(poiId: String, currentLabel: String?) {
        val context = fragment.requireContext()
        val editText = android.widget.EditText(context).apply {
            setText(currentLabel)
            filters = arrayOf(android.text.InputFilter.LengthFilter(50)) // Limit label length
            // Set input type to prevent newlines
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        
        // Helper function to handle label submission
        fun submitLabel() {
            val newLabel = editText.text.toString().takeIf { it.isNotBlank() }
            annotationViewModel.updatePointOfInterest(poiId, newLabel = newLabel)
            // Force map redraw to update POI layer with new label
            onAnnotationChanged?.invoke()
        }
        
        // Add editor action listener to handle Enter key
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitLabel()
                true // Consume the event
            } else {
                false // Don't consume other events
            }
        }
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(fragment.getString(R.string.edit_label))
            .setView(editText)
            .setPositiveButton(fragment.getString(R.string.ok)) { _, _ ->
                submitLabel()
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }

    private fun showPolygonFanMenu(center: PointF, polygonId: String) {
        Log.d("AnnotationController", "showPolygonFanMenu: center=$center, polygonId=$polygonId")
        val options = listOf(
            // Shape options for adding POIs within polygon
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE),
            // Edit option for the polygon itself
            FanMenuView.Option.EditPolygon(polygonId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        
        // Get polygon center for menu positioning
        val polygon = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Polygon>().find { it.id == polygonId }
        val polygonLatLng = polygon?.let { poly ->
            if (poly.points.isNotEmpty()) {
                val avgLat = poly.points.map { it.lt }.average()
                val avgLng = poly.points.map { it.lng }.average()
                LatLng(avgLat, avgLng)
            } else null
        }
        
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Shape -> {
                        // Set current shape and show color menu for POI creation within polygon
                        annotationViewModel.setCurrentShape(option.shape)
                        showPolygonColorMenu(center, option.shape, polygonId)
                        return true // Keep menu open for transition
                    }
                    is FanMenuView.Option.EditPolygon -> {
                        // Show polygon edit menu
                        showPolygonEditMenu(center, option.polygonId)
                        return true // Keep menu open for transition
                    }
                    else -> {}
                }
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for POLYGON")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, polygonLatLng)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for POLYGON")
    }

    fun showPolygonEditMenu(center: PointF, polygonId: String) {
        Log.d("AnnotationController", "showPolygonEditMenu: center=$center, polygonId=$polygonId")
        val polygon = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Polygon>().find { it.id == polygonId } ?: return
        val options = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK),
            FanMenuView.Option.Timer(polygonId),
            FanMenuView.Option.Label(polygonId),
            FanMenuView.Option.Delete(polygonId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        
        // Get polygon center for menu positioning
        val polygonLatLng = if (polygon.points.isNotEmpty()) {
            val avgLat = polygon.points.map { it.lt }.average()
            val avgLng = polygon.points.map { it.lng }.average()
            LatLng(avgLat, avgLng)
        } else null
        
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                Log.d("AnnotationController", "Polygon edit menu option selected: $option")
                when (option) {
                    is FanMenuView.Option.Color -> {
                        Log.d("AnnotationController", "Updating polygon color to: ${option.color}")
                        updatePolygonColor(polygonId, option.color)
                    }
                    is FanMenuView.Option.Timer -> {
                        Log.d("AnnotationController", "Setting polygon expiration")
                        setAnnotationExpiration(polygonId)
                    }
                    is FanMenuView.Option.Label -> {
                        Log.d("AnnotationController", "Showing polygon label dialog")
                        showPolygonLabelEditDialog(polygonId, polygon.label)
                    }
                    is FanMenuView.Option.Delete -> {
                        Log.d("AnnotationController", "Deleting polygon: ${option.id}")
                        deletePoi(option.id)
                    }
                    else -> {}
                }
                // Force map redraw to update polygon layer
                onAnnotationChanged?.invoke()
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for POLYGON EDIT")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, polygonLatLng)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for POLYGON EDIT")
    }

    fun updatePolygonColor(polygonId: String, color: AnnotationColor) {
        annotationViewModel.updatePolygon(polygonId, newColor = color)
    }

    private fun showPolygonLabelEditDialog(polygonId: String, currentLabel: String?) {
        val context = fragment.requireContext()
        val editText = android.widget.EditText(context).apply {
            setText(currentLabel)
            filters = arrayOf(android.text.InputFilter.LengthFilter(50)) // Limit label length
            // Set input type to prevent newlines
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        
        // Helper function to handle label submission
        fun submitLabel() {
            val newLabel = editText.text.toString().takeIf { it.isNotBlank() }
            annotationViewModel.updatePolygon(polygonId, newLabel = newLabel)
            // Force map redraw to update polygon layer with new label
            onAnnotationChanged?.invoke()
        }
        
        // Add editor action listener to handle Enter key
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitLabel()
                true // Consume the event
            } else {
                false // Don't consume other events
            }
        }
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(fragment.getString(R.string.edit_polygon_label))
            .setView(editText)
            .setPositiveButton(fragment.getString(R.string.ok)) { _, _ ->
                submitLabel()
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }

    fun showPolygonColorMenu(center: PointF, shape: PointShape, polygonId: String) {
        val colorOptions = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        
        // Get polygon center for POI placement
        val polygon = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Polygon>().find { it.id == polygonId }
        val polygonLatLng = polygon?.let { poly ->
            if (poly.points.isNotEmpty()) {
                val avgLat = poly.points.map { it.lt }.average()
                val avgLng = poly.points.map { it.lng }.average()
                LatLng(avgLat, avgLng)
            } else null
        }
        
        fanMenuView.showAt(center, colorOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                if (option is FanMenuView.Option.Color) {
                    annotationViewModel.setCurrentColor(option.color)
                    addPoiFromPolygonFanMenu(shape, option.color)
                    return false // Dismiss menu after POI creation
                }
                return false
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, polygonLatLng)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
    }

    fun addPoiFromPolygonFanMenu(shape: PointShape, color: AnnotationColor) {
        // Use the stored touch location for POI placement
        val latLng = pendingPolygonTouchLatLng
        if (latLng != null) {
            annotationViewModel.setCurrentShape(shape)
            annotationViewModel.setCurrentColor(color)
            annotationViewModel.addPointOfInterest(latLng)
            fanMenuView.visibility = View.GONE
            pendingPolygonTouchLatLng = null // Clear the stored location
            onAnnotationChanged?.invoke()
            val nonPoiAnnotations = annotationViewModel.uiState.value.annotations.filterNot { it is MapAnnotation.PointOfInterest }
            annotationOverlayView.updateAnnotations(nonPoiAnnotations)
            annotationOverlayView.invalidate()
            
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.poi_added_to_polygon), Toast.LENGTH_SHORT).show()
        } else {
            Log.e("AnnotationController", "No touch location stored for polygon POI creation")
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.error_could_not_place_poi), Toast.LENGTH_SHORT).show()
        }
    }

    fun showPolygonLabel(polygonId: String, screenPosition: PointF) {
        Log.d("AnnotationController", "showPolygonLabel: polygonId=$polygonId, screenPosition=$screenPosition")
        // Find the polygon annotation to get its details
        val polygon = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Polygon>().find { it.id == polygonId }
        if (polygon != null) {
            Log.d("AnnotationController", "Found polygon: ${polygon.label}, points: ${polygon.points.size}")
            popoverManager.showPolygonPopover(polygonId, polygon)
            Log.d("AnnotationController", "showPolygonLabel: called popoverManager.showPolygonPopover")
        } else {
            Log.e("AnnotationController", "Polygon not found: $polygonId")
        }
    }

    private fun showAreaMenu(center: PointF, areaId: String) {
        Log.d("AnnotationController", "showAreaMenu: center=$center, areaId=$areaId")
        val area = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Area>().find { it.id == areaId } ?: return
        val options = listOf(
            // Shape options for adding POIs within area
            FanMenuView.Option.Shape(PointShape.CIRCLE),
            FanMenuView.Option.Shape(PointShape.EXCLAMATION),
            FanMenuView.Option.Shape(PointShape.SQUARE),
            FanMenuView.Option.Shape(PointShape.TRIANGLE),
            // Edit option for the area itself
            FanMenuView.Option.EditArea(areaId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val areaLatLng = LatLng(area.center.lt, area.center.lng)
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Shape -> {
                        // Set current shape and show color menu for POI creation within area
                        annotationViewModel.setCurrentShape(option.shape)
                        showAreaColorMenu(center, option.shape, areaId)
                        return true // Keep menu open for transition
                    }
                    is FanMenuView.Option.EditArea -> {
                        // Show area edit menu
                        showAreaEditMenu(center, option.areaId)
                        return true // Keep menu open for transition
                    }
                    else -> {}
                }
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for AREA")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, areaLatLng)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for AREA")
    }
    
    fun updateAreaColor(areaId: String, color: AnnotationColor) {
        annotationViewModel.updateArea(areaId, newColor = color)
    }
    
    fun showAreaEditMenu(center: PointF, areaId: String) {
        Log.d("AnnotationController", "showAreaEditMenu: center=$center, areaId=$areaId")
        val area = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Area>().find { it.id == areaId } ?: return
        val options = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK),
            FanMenuView.Option.Timer(areaId),
            FanMenuView.Option.Label(areaId),
            FanMenuView.Option.Delete(areaId)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val areaLatLng = LatLng(area.center.lt, area.center.lng)
        fanMenuView.showAt(center, options, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                when (option) {
                    is FanMenuView.Option.Color -> {
                        Log.d("AnnotationController", "Updating area color to: ${option.color}")
                        updateAreaColor(areaId, option.color)
                    }
                    is FanMenuView.Option.Timer -> {
                        Log.d("AnnotationController", "Setting area expiration")
                        setAnnotationExpiration(areaId)
                    }
                    is FanMenuView.Option.Label -> {
                        Log.d("AnnotationController", "Showing area label dialog")
                        showAreaLabelEditDialog(areaId, area.label)
                    }
                    is FanMenuView.Option.Delete -> {
                        Log.d("AnnotationController", "Deleting area: ${option.id}")
                        deletePoi(option.id)
                    }
                    else -> {}
                }
                // Force map redraw to update area layer
                onAnnotationChanged?.invoke()
                return false
            }
            override fun onMenuDismissed() {
                Log.d("AnnotationController", "fanMenuView.onMenuDismissed for AREA EDIT")
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, areaLatLng)
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
        Log.d("AnnotationController", "fanMenuView.visibility set to VISIBLE for AREA EDIT")
    }
    
    private fun showAreaColorMenu(center: PointF, shape: PointShape, areaId: String) {
        val colorOptions = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        
        // Use the stored touch location for POI placement
        val touchLatLng = pendingPoiLatLng
        
        fanMenuView.showAt(center, colorOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                if (option is FanMenuView.Option.Color) {
                    annotationViewModel.setCurrentColor(option.color)
                    addPoiFromAreaFanMenu(shape, option.color, areaId)
                    return false // Dismiss menu after POI creation
                }
                return false
            }
            override fun onMenuDismissed() {
                fanMenuView.visibility = View.GONE
            }
        }, screenSize, touchLatLng?.let { LatLng(it.latitude, it.longitude) })
        fanMenuView.bringToFront()
        fanMenuView.visibility = View.VISIBLE
    }
    
    fun addPoiFromAreaFanMenu(shape: PointShape, color: AnnotationColor, areaId: String) {
        // Use the actual touch location for POI placement
        val latLng = pendingPoiLatLng
        if (latLng != null) {
            Log.d("AnnotationController", "Creating POI within area: shape=$shape, color=$color, areaId=$areaId, latLng=$latLng")
            annotationViewModel.setCurrentShape(shape)
            annotationViewModel.setCurrentColor(color)
            annotationViewModel.addPointOfInterest(latLng)
            fanMenuView.visibility = View.GONE
            pendingPoiLatLng = null // Clear the stored location
            onAnnotationChanged?.invoke()
            val nonPoiAnnotations = annotationViewModel.uiState.value.annotations.filterNot { it is MapAnnotation.PointOfInterest }
            annotationOverlayView.updateAnnotations(nonPoiAnnotations)
            annotationOverlayView.invalidate()
            
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.poi_added_to_area), Toast.LENGTH_SHORT).show()
        } else {
            Log.e("AnnotationController", "No touch location stored for area POI creation")
            Toast.makeText(fragment.requireContext(), fragment.getString(R.string.error_could_not_place_poi), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAreaLabelEditDialog(areaId: String, currentLabel: String?) {
        val context = fragment.requireContext()
        val editText = android.widget.EditText(context).apply {
            setText(currentLabel)
            filters = arrayOf(android.text.InputFilter.LengthFilter(50)) // Limit label length
            // Set input type to prevent newlines
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        
        // Helper function to handle label submission
        fun submitLabel() {
            val newLabel = editText.text.toString().takeIf { it.isNotBlank() }
            annotationViewModel.updateArea(areaId, newLabel = newLabel)
            // Force map redraw to update area layer with new label
            onAnnotationChanged?.invoke()
        }
        
        // Add editor action listener to handle Enter key
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                submitLabel()
                true // Consume the event
            } else {
                false // Don't consume other events
            }
        }
        
        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(fragment.getString(R.string.edit_area_label))
            .setView(editText)
            .setPositiveButton(fragment.getString(R.string.ok)) { _, _ ->
                submitLabel()
            }
            .setNegativeButton(fragment.getString(R.string.cancel), null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }

    private fun startAreaDrawing(center: PointF) {
        Log.d("AnnotationController", "startAreaDrawing: center=$center")
        val latLng = pendingPoiLatLng ?: return
        
        isAreaDrawingMode = true
        tempAreaCenter = latLng
        tempAreaRadiusPixels = 300f // Default 300 pixels
        
        // Convert pixels to meters (approximate)
        val projection = mapController?.mapLibreMap?.projection
        if (projection != null) {
            val centerScreen = projection.toScreenLocation(latLng)
            val edgeScreen = PointF(centerScreen.x + tempAreaRadiusPixels, centerScreen.y)
            val edgeLatLng = projection.fromScreenLocation(edgeScreen)
            tempAreaRadius = calculateDistance(latLng, edgeLatLng)
            Log.d("AnnotationController", "Initial area radius (from 300px): $tempAreaRadius m at latLng=$latLng")
        } else {
            // Fallback: rough conversion (1 pixel ≈ 1 meter at zoom level 15)
            tempAreaRadius = tempAreaRadiusPixels.toDouble()
            Log.w("AnnotationController", "Projection null; using fallback radius=${tempAreaRadius} m")
        }
        
        // Show color menu for area
        showAreaColorMenu(center)
        
        // Update overlay to show temporary area
        annotationOverlayView.setTempArea(tempAreaCenter, tempAreaRadius)
        annotationOverlayView.invalidate()
        
        // Set up area radius change listener
        annotationOverlayView.areaRadiusChangeListener = object : AnnotationOverlayView.OnAreaRadiusChangeListener {
            override fun onAreaRadiusChanged(newRadius: Double) {
                Log.d("AnnotationController", "Area radius changed from $tempAreaRadius to $newRadius")
                tempAreaRadius = newRadius
                annotationOverlayView.setTempArea(tempAreaCenter, tempAreaRadius)
                annotationOverlayView.invalidate()
            }
            
            override fun onAreaDrawingFinished() {
                // Area drawing is finished, but we'll keep the temporary area visible
                // until the user selects a color from the menu
                Log.d("AnnotationController", "Area drawing finished with radius: $tempAreaRadius")
                
                // If we're still in area drawing mode and have a valid area, show the color menu
                if (isAreaDrawingMode && tempAreaCenter != null && tempAreaRadius > 0) {
                    Log.d("AnnotationController", "Showing color menu for area creation")
                    val center = PointF(binding.root.width / 2f, binding.root.height / 2f)
                    showAreaColorMenu(center)
                } else {
                    Log.d("AnnotationController", "Not showing color menu: isAreaDrawingMode=$isAreaDrawingMode, tempAreaCenter=$tempAreaCenter, tempAreaRadius=$tempAreaRadius")
                }
            }
        }
    }
    
    private fun showAreaColorMenu(center: PointF) {
        val colorOptions = listOf(
            FanMenuView.Option.Color(AnnotationColor.GREEN),
            FanMenuView.Option.Color(AnnotationColor.YELLOW),
            FanMenuView.Option.Color(AnnotationColor.RED),
            FanMenuView.Option.Color(AnnotationColor.BLACK)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val menuLatLng = tempAreaCenter?.let { LatLng(it.latitude, it.longitude) }
        fanMenuView.showAt(center, colorOptions, object : FanMenuView.OnOptionSelectedListener {
            override fun onOptionSelected(option: FanMenuView.Option): Boolean {
                if (option is FanMenuView.Option.Color) {
                    annotationViewModel.setCurrentColor(option.color)
                    createAreaFromFanMenu(option.color)
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
    
    private fun createAreaFromFanMenu(color: AnnotationColor) {
        val center = tempAreaCenter ?: return
        Log.d("AnnotationController", "createAreaFromFanMenu: center=$center, radius=$tempAreaRadius, color=$color")
        annotationViewModel.setCurrentColor(color)

        var radiusMeters = tempAreaRadius
        if (radiusMeters <= 0.0) {
            // Recompute defensively from pixels to meters to avoid creating zero-radius areas
            val projection = mapController?.mapLibreMap?.projection
            radiusMeters = if (projection != null) {
                val centerScreen = projection.toScreenLocation(center)
                val edgeScreen = PointF(centerScreen.x + tempAreaRadiusPixels, centerScreen.y)
                val edgeLatLng = projection.fromScreenLocation(edgeScreen)
                val recomputed = calculateDistance(center, edgeLatLng)
                Log.w("AnnotationController", "Recomputed area radius from pixels: $recomputed m (was ${tempAreaRadius})")
                recomputed
            } else {
                Log.w("AnnotationController", "Projection null while recomputing; defaulting radius to 50m")
                50.0
            }
        }

        annotationViewModel.addArea(center, radiusMeters)
        fanMenuView.visibility = View.GONE
        finishAreaDrawing()
        onAnnotationChanged?.invoke()
        
        // Force map redraw to update area layer
        val nonPoiAnnotations = annotationViewModel.uiState.value.annotations.filterNot { it is MapAnnotation.PointOfInterest }
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
        annotationOverlayView.invalidate()
        
        Log.d("AnnotationController", "Area created successfully with radius: $tempAreaRadius meters")
        Toast.makeText(fragment.requireContext(), fragment.getString(R.string.area_created), Toast.LENGTH_SHORT).show()
    }
    
    private fun finishAreaDrawing() {
        isAreaDrawingMode = false
        tempAreaCenter = null
        tempAreaRadius = 0.0
        tempAreaRadiusPixels = 300f
        annotationOverlayView.setTempArea(null, 0.0)
        annotationOverlayView.areaRadiusChangeListener = null
        pendingPoiLatLng = null
    }
    
    private fun calculateDistance(point1: LatLng, point2: LatLng): Double {
        val lat1 = Math.toRadians(point1.latitude)
        val lon1 = Math.toRadians(point1.longitude)
        val lat2 = Math.toRadians(point2.latitude)
        val lon2 = Math.toRadians(point2.longitude)

        return haversine(lat1, lon1, lat2, lon2)
    }

    fun showAreaLabel(areaId: String, screenPosition: PointF) {
        Log.d("AnnotationController", "showAreaLabel: areaId=$areaId, screenPosition=$screenPosition")
        // Find the area annotation to get its details
        val area = annotationViewModel.uiState.value.annotations.filterIsInstance<MapAnnotation.Area>().find { it.id == areaId }
        if (area != null) {
            Log.d("AnnotationController", "Found area: ${area.label}, radius: ${area.radius}")
            popoverManager.showAreaPopover(areaId, area)
            Log.d("AnnotationController", "showAreaLabel: called popoverManager.showAreaPopover")
        } else {
            Log.e("AnnotationController", "Area not found: $areaId")
        }
    }

    // Add throttled camera move handler
    fun onCameraMoveThrottled(map: MapLibreMap) {
        // Minimal updates: e.g., popover positions only
        popoverManager.updatePopoverPosition()
        // No full renders here—native layers auto-update
    }
}