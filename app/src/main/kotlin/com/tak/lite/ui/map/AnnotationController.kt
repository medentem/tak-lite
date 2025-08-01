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
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.viewmodel.AnnotationViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import kotlinx.coroutines.launch
import org.maplibre.android.annotations.Marker
import org.maplibre.android.annotations.Polygon
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
    private lateinit var lineToolConfirmButton: View
    private lateinit var lineToolCancelButton: View
    private lateinit var lineToolButtonFrame: View
    private lateinit var lineToolLabel: View
    private val poiMarkers = mutableMapOf<String, Marker>()
    private val areaPolygons = mutableMapOf<String, Polygon>()
    private var lastOverlayProjection: org.maplibre.android.maps.Projection? = null
    private var lastOverlayAnnotations: List<MapAnnotation> = emptyList()
    private var lastPoiAnnotations: List<MapAnnotation.PointOfInterest> = emptyList()
    var mapController: MapController? = null
    
    // === NATIVE CLUSTERING SUPPORT ===
    private var clusteredLayerManager: ClusteredLayerManager;
    var clusteringConfig: ClusteringConfig
    private var lastPeerUpdate = 0L
    private val PEER_UPDATE_THROTTLE_MS = 100L
    
    // === HYBRID POPOVER SUPPORT ===
    lateinit var popoverManager: HybridPopoverManager
    private var lastPopoverUpdate = 0L
    private val POPOVER_UPDATE_THROTTLE_MS = 10L
    
    // === POI TIMER SUPPORT ===
    private var poiTimerManager: PoiTimerManager? = null
    
    // Getter for timer manager
    val timerManager: PoiTimerManager? get() = poiTimerManager

    init {
        clusteringConfig = ClusteringConfig.getDefault()
        clusteredLayerManager = ClusteredLayerManager(mapLibreMap, clusteringConfig)
        popoverManager = HybridPopoverManager(mapLibreMap, binding.root, meshNetworkViewModel)
        Log.d("PeerDotDebug", "AnnotationController initialized with clustering config: $clusteringConfig")
    }
    
    // === ENHANCED: Convert peer locations to clustered GeoJSON FeatureCollection ===
    private fun peerLocationsToClusteredFeatureCollection(peerLocations: Map<String, com.tak.lite.model.PeerLocationEntry>): FeatureCollection {
        Log.d("PeerDotDebug", "Creating FeatureCollection from ${peerLocations.size} peer locations")

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
                    "GREEN" -> "#4CAF50"
                    "YELLOW" -> "#FFC107"
                    "RED" -> "#F44336"
                    else -> "#4CAF50" // default green
                }
            }
            
            val borderColor = if (isStale) {
                when (userStatus) {
                    "GREEN" -> "#4CAF50"
                    "YELLOW" -> "#FFC107"
                    "RED" -> "#F44336"
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

            Log.d("PeerDotDebug", "peerId=$peerId, userStatus=$userStatus, isStale=$isStale, fillColor=$fillColor, borderColor=$borderColor")
            feature
        }
        
        val featureCollection = FeatureCollection.fromFeatures(features)
        Log.d("PeerDotDebug", "Created FeatureCollection with ${featureCollection.features()?.size} features")

        return featureCollection
    }

    // === ENHANCED: Convert POI annotations to clustered GeoJSON FeatureCollection ===
    private fun poiAnnotationsToClusteredFeatureCollection(pois: List<MapAnnotation.PointOfInterest>): FeatureCollection {
        val features = pois.map { poi ->
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
            feature
        }
        return FeatureCollection.fromFeatures(features)
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
                } else {
                    Log.d("PoiDebug", "Icon already exists: $iconName")
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
                    this.color = android.graphics.Color.WHITE
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
                    this.color = android.graphics.Color.WHITE
                    style = android.graphics.Paint.Style.FILL
                }
                canvas.drawCircle(centerX, dotCenterY, dotRadius, dotPaint)
            }
        }
        
        return bitmap
    }

    // Initialize timer manager when map is ready
    fun initializeTimerManager(mapLibreMap: MapLibreMap?) {
        if (poiTimerManager == null && mapLibreMap != null) {
            poiTimerManager = PoiTimerManager(mapLibreMap, annotationViewModel)
            Log.d("AnnotationController", "Timer manager initialized")
        }
    }

    // Overlay and menu setup
    fun setupAnnotationOverlay(mapLibreMap: MapLibreMap?) {
        mapLibreMap?.addOnCameraMoveListener {
            // IMMEDIATE: Only update projection for visual sync
            annotationOverlayView.setProjection(mapLibreMap.projection)
            
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
            
            // Initialize timer manager and setup POI timer layers
            initializeTimerManager(mapLibreMap)
            poiTimerManager?.setupTimerLayers()
            
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
                    style.addLayer(layer)
                    Log.d("PeerDotDebug", "Non-clustered peer dots layer created with properties: circleColor=${layer.circleColor}, circleStrokeColor=${layer.circleStrokeColor}")

                    // Add invisible hit area layer for easier tapping (non-clustered)
                    val hitAreaLayer = CircleLayer("peer-dots-hit-area", sourceId)
                        .withProperties(
                            PropertyFactory.circleColor("#FFFFFF"), // Transparent
                            PropertyFactory.circleOpacity(0f),
                            PropertyFactory.circleRadius(20f), // Larger hit area
                        )
                    style.addLayer(hitAreaLayer)
                    Log.d("PeerDotDebug", "Non-clustered peer hit area layer created")
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
                    style.addLayer(layer)
                    Log.d("PoiDebug", "Non-clustered POI layer created")
                    
                    // Retry timer layer setup now that POI layer exists
                    poiTimerManager?.retrySetupTimerLayers()
                } else {
                    Log.d("PoiDebug", "Non-clustered POI layer already exists")
                }
            }
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
        
        Log.d("PeerDotDebug", "updatePeerDotsOnMap: peerCount=${peerLocations.size}, peerIds=${peerLocations.keys}")
        if (peerLocations.isNotEmpty()) {
            val firstPeer = peerLocations.entries.first()
            Log.d("PeerDotDebug", "First peer: ${firstPeer.key} at ${firstPeer.value.latitude}, ${firstPeer.value.longitude}")
        }
        
        if (clusteringConfig.enablePeerClustering) {
            Log.d("PeerDotDebug", "Using native clustering")
            Log.d("PeerDotDebug", "Clustering config: radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")
            Log.d("PeerDotDebug", "Current zoom: ${mapLibreMap?.cameraPosition?.zoom}, should cluster: ${mapLibreMap?.cameraPosition?.zoom?.let { it <= clusteringConfig.peerClusterMaxZoom }}")
            mapLibreMap?.getStyle { style ->
                val featureCollection = peerLocationsToClusteredFeatureCollection(peerLocations)
                Log.d("PeerDotDebug", "Created FeatureCollection with ${featureCollection.features()?.size} features")
                
                // Debug: Log the GeoJSON string
                val geoJsonString = featureCollection.toJson()
                Log.d("PeerDotDebug", "GeoJSON string length: ${geoJsonString.length}")
                Log.d("PeerDotDebug", "GeoJSON string preview: ${geoJsonString.take(500)}...")
                
                // Check if source already exists
                val existingSource = style.getSourceAs<GeoJsonSource>(ClusteredLayerManager.PEER_CLUSTERED_SOURCE)
                if (existingSource != null) {
                    // Update existing source
                    Log.d("PeerDotDebug", "Updating existing clustered source")
                    existingSource.setGeoJson(featureCollection)
                    Log.d("PeerDotDebug", "Updated existing clustered source")
                } else {
                    // Create new source with data and clustering options
                    Log.d("PeerDotDebug", "Creating new clustered source")
                    try {
                        clusteredLayerManager.setupPeerClusteredLayer(geoJsonString)
                        Log.d("PeerDotDebug", "Created new clustered source with user config: radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")
                    } catch (e: Exception) {
                        Log.e("PeerDotDebug", "Failed to create clustered source: ${e.message}", e)
                    }
                }
                Log.d("PeerDotDebug", "Peer clustered GL layer updated - SUCCESS")
            }
        } else {
            Log.d("PeerDotDebug", "Using regular peer dots layer")
            mapLibreMap?.getStyle { style ->
                val source = style.getSourceAs<GeoJsonSource>("peer-dots-source")
                if (source != null) {
                    val featureCollection = peerLocationsToClusteredFeatureCollection(peerLocations)
                    Log.d("PeerDotDebug", "Setting regular GeoJSON with ${featureCollection.features()?.size} features")
                    source.setGeoJson(featureCollection)
                    Log.d("PeerDotDebug", "Regular peer dots layer updated - SUCCESS")
                } else {
                    Log.e("PeerDotDebug", "Regular peer dots source not found")
                }
            }
        }
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
                    clusteredLayerManager?.setupPoiClusteredLayer(featureCollection.toJson())
                    Log.d("PeerDotDebug", "Created new clustered source with user config: radius=${clusteringConfig.clusterRadius}, maxZoom=${clusteringConfig.peerClusterMaxZoom}")
                } catch (e: Exception) {
                    Log.e("PeerDotDebug", "Failed to create clustered source: ${e.message}", e)
                }
                
                // Retry timer layer setup now that clustered POI layer exists
                poiTimerManager?.retrySetupTimerLayers()
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
    
    fun handleMapLongPress(latLng: org.maplibre.android.geometry.LatLng, mapLibreMap: MapLibreMap): Boolean {
        val projection = mapLibreMap.projection
        val screenPoint = projection.toScreenLocation(latLng)
        
        // First check if we're long pressing on a POI (including fallback layer)
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
        if (poiFeature != null) {
            val poiId = poiFeature.getStringProperty("poiId")
            showPoiEditMenu(screenPoint, poiId)
            return true
        } else {
            // If not on a POI, create a new POI
            val center = PointF(screenPoint.x, screenPoint.y)
            pendingPoiLatLng = latLng
            showFanMenu(center)
            return true
        }
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
            
            val screenPt = mapLibreMap.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(lat, lng))
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

    // POI/line/area editing
    fun getPoiById(poiId: String): MapAnnotation.PointOfInterest? {
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
        val marker = poiMarkers[poiId]
        marker?.let { (annotationOverlayView.context as? MapLibreMap)?.removeAnnotation(it) }
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
        
        // Update the annotation overlay with current annotations (excluding POIs)
        val state = annotationViewModel.uiState.value
        val allAnnotations = state.annotations
        val nonPoiAnnotations = allAnnotations.filterNot { it is MapAnnotation.PointOfInterest }
        val poiAnnotations = allAnnotations.filterIsInstance<MapAnnotation.PointOfInterest>()
        
        Log.d("PoiDebug", "renderAllAnnotations: total=${allAnnotations.size}, nonPoi=${nonPoiAnnotations.size}, poi=${poiAnnotations.size}")
        Log.d("PoiDebug", "POI annotations: ${poiAnnotations.map { "${it.id}:${it.label}" }}")
        
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
        annotationOverlayView.invalidate()
        
        // Update native POI annotations
        updatePoiAnnotationsOnMap(mapLibreMap, poiAnnotations)
    }

    fun syncAnnotationOverlayView(mapLibreMap: MapLibreMap?) {
        Log.d("PeerDotDebug", "syncAnnotationOverlayView called")
        
        val currentProjection = mapLibreMap?.projection
        val currentAnnotations = annotationViewModel.uiState.value.annotations
        var changed = false
        if (currentProjection != lastOverlayProjection) {
            annotationOverlayView.setProjection(currentProjection)
            lastOverlayProjection = currentProjection
            changed = true
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
        
        Log.d("PoiDebug", "syncAnnotationOverlayView: total=${currentAnnotations.size}, nonPoi=${nonPoiAnnotations.size}, poi=${poiAnnotations.size}, changed=$poiAnnotationsChanged")
        
        annotationOverlayView.updateAnnotations(nonPoiAnnotations)
        lastOverlayAnnotations = nonPoiAnnotations
    }

    // Utility
    fun annotationColorToAndroidColor(color: AnnotationColor): Int {
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
            FanMenuView.Option.Shape(PointShape.TRIANGLE)
        )
        val screenSize = PointF(binding.root.width.toFloat(), binding.root.height.toFloat())
        val menuLatLng = pendingPoiLatLng?.let { LatLng(it.latitude, it.longitude) }
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
                            .setTitle("Set Expiration (minutes)")
                            .setView(input)
                            .setPositiveButton("OK") { _, _ ->
                                submitExpiration()
                            }
                            .setNegativeButton("Cancel", null)
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
        val line = annotationViewModel.uiState.value.annotations.filterIsInstance<com.tak.lite.model.MapAnnotation.Line>().find { it.id == lineId } ?: return
        val activity = fragment.requireActivity() as? androidx.fragment.app.FragmentActivity
        if (activity != null) {
            Log.d("AnnotationController", "Showing ElevationChartBottomSheet for lineId=$lineId")
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
                val channel = messageViewModel.getOrCreateDirectMessageChannel(peerId, peerName)

                if (channel != null) {
                    // Launch the MessageActivity using the companion object method
                    fragment.requireContext().startActivity(MessageActivity.createIntent(fragment.requireContext(), channel.id))
                }
            } catch (e: Exception) {
                Log.e("AnnotationController", "Error handling direct message: ${e.message}", e)
                Toast.makeText(fragment.requireContext(), "Failed to start direct message", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun handleLocationRequest(peerId: String) {
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val peerName = meshNetworkViewModel.getPeerName(peerId)
            Toast.makeText(fragment.requireContext(), "Waiting for location update from $peerName", Toast.LENGTH_LONG).show()
            meshNetworkViewModel.requestPeerLocation(peerId, onLocationReceived = { timeout ->
                fragment.viewLifecycleOwner.lifecycleScope.launch {
                    if (timeout) {
                        Toast.makeText(fragment.requireContext(), "$peerName did not respond", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(fragment.requireContext(), "Received updated location for $peerName", Toast.LENGTH_LONG).show()
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
            .setTitle("Edit Label")
            .setView(editText)
            .setPositiveButton("OK") { _, _ ->
                submitLabel()
            }
            .setNegativeButton("Cancel", null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }
}