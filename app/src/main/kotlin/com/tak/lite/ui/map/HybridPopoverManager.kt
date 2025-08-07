package com.tak.lite.ui.map

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tak.lite.R
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.haversine
import com.tak.lite.viewmodel.MeshNetworkViewModel
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

class HybridPopoverManager(
    private val mapLibreMap: MapLibreMap,
    private val rootView: ViewGroup,
    private val meshNetworkViewModel: MeshNetworkViewModel
) {
    companion object {
        private const val TAG = "HybridPopoverManager"
        private const val POPOVER_LAYER = "popover-position"
        private const val POPOVER_SOURCE = "popover-source"
        private const val POPOVER_TAG = "MapPopover"
    }

    private var currentPopover: PopoverData? = null
    private var popoverView: View? = null
    private val popoverDismissHandler = Handler(Looper.getMainLooper())
    
    init {
        setupPopoverLayer()
    }

    private fun setupPopoverLayer() {
        Log.d(TAG, "setupPopoverLayer: starting setup")
        mapLibreMap.getStyle { style ->
            Log.d(TAG, "setupPopoverLayer: got style, creating source")
            
            // Create invisible source for positioning
            val source = GeoJsonSource(POPOVER_SOURCE, FeatureCollection.fromFeatures(arrayOf()))
            style.addSource(source)
            Log.d(TAG, "setupPopoverLayer: added source: $POPOVER_SOURCE")
            
            // Create invisible symbol layer for positioning
            val layer = SymbolLayer(POPOVER_LAYER, POPOVER_SOURCE)
                .withProperties(
                    PropertyFactory.iconImage(""), // No icon
                    PropertyFactory.iconSize(0f), // Invisible
                    PropertyFactory.iconAllowOverlap(true)
                )
            style.addLayer(layer)
            Log.d(TAG, "Setup popover layer: $POPOVER_LAYER")
        }
    }

    fun showPeerPopover(peerId: String, peerName: String?, lastHeard: Long?, position: LatLng) {
        Log.d(TAG, "showPeerPopover called: peerId=$peerId, peerName=$peerName, position=$position")
        val content = buildPeerPopoverContent(peerId, peerName, lastHeard)
        Log.d(TAG, "Built content: $content")
        showPopover(PopoverData(
            id = "peer_$peerId",
            type = PopoverType.PEER,
            position = position,
            content = content,
            timestamp = System.currentTimeMillis(),
            autoDismissTime = System.currentTimeMillis() + 5000L
        ))
    }

    fun showPoiPopover(poiId: String, poi: MapAnnotation.PointOfInterest) {
        val content = buildPoiPopoverContent(poi)
        showPopover(PopoverData(
            id = "poi_$poiId",
            type = PopoverType.POI,
            position = poi.position.toMapLibreLatLng(),
            content = content,
            timestamp = System.currentTimeMillis(),
            autoDismissTime = System.currentTimeMillis() + 8000L
        ))
    }

    fun showPolygonPopover(polygonId: String, polygon: MapAnnotation.Polygon) {
        val content = buildPolygonPopoverContent(polygon)
        // Use polygon center for positioning
        val centerLat = polygon.points.map { it.lt }.average()
        val centerLng = polygon.points.map { it.lng }.average()
        val centerPosition = org.maplibre.android.geometry.LatLng(centerLat, centerLng)
        
        showPopover(PopoverData(
            id = "polygon_$polygonId",
            type = PopoverType.POLYGON,
            position = centerPosition,
            content = content,
            timestamp = System.currentTimeMillis(),
            autoDismissTime = System.currentTimeMillis() + 8000L
        ))
    }

    private fun showPopover(popoverData: PopoverData) {
        Log.d(TAG, "Showing popover: ${popoverData.id}")
        Log.d(TAG, "Current popover state: $currentPopover")
        hideCurrentPopover()
        currentPopover = popoverData

        // 1. Create invisible GL feature for positioning
        val feature = Feature.fromGeometry(Point.fromLngLat(
            popoverData.position.longitude, 
            popoverData.position.latitude
        ))
        feature.addStringProperty("popoverId", popoverData.id)
        
        Log.d(TAG, "Created GL feature: $feature")

        mapLibreMap.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(POPOVER_SOURCE)
            if (source != null) {
                source.setGeoJson(FeatureCollection.fromFeatures(arrayOf(feature)))
                Log.d(TAG, "Updated GL source with feature")
            } else {
                Log.e(TAG, "GL source not found: $POPOVER_SOURCE")
            }
        }

        // 2. Create and position custom view
        createPopoverView(popoverData)

        // 3. Schedule auto-dismiss
        scheduleAutoDismiss(popoverData.autoDismissTime)
        
        Log.d(TAG, "Popover setup completed for: ${popoverData.id}")
    }

    private fun createPopoverView(popoverData: PopoverData) {
        Log.d(TAG, "createPopoverView: creating popover for ${popoverData.id}")
        
        // Remove existing popover view
        popoverView?.let { 
            Log.d(TAG, "Removing existing popover view")
            rootView.removeView(it) 
        }

        // Inflate new popover view
        val inflater = LayoutInflater.from(rootView.context)
        popoverView = inflater.inflate(R.layout.popover_content, rootView, false)
        popoverView?.tag = POPOVER_TAG
        
        // Set explicit layout parameters
        val layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        popoverView?.layoutParams = layoutParams
        
        Log.d(TAG, "Created popover view: $popoverView")

        // Set content based on type
        when (popoverData.type) {
            PopoverType.PEER -> setupPeerPopoverContent(popoverView!!, popoverData)
            PopoverType.POI -> setupPoiPopoverContent(popoverView!!, popoverData)
            PopoverType.POLYGON -> setupPolygonPopoverContent(popoverView!!, popoverData)
        }

        // Position the view
        positionPopoverView(popoverData.position)

        // Add to root view
        rootView.addView(popoverView)
        Log.d(TAG, "Added popover view to root view. Root view child count: ${rootView.childCount}")
        Log.d(TAG, "Root view class: ${rootView.javaClass.simpleName}")
        Log.d(TAG, "Popover view parent: ${popoverView?.parent}")

        // Ensure view is visible
        popoverView?.visibility = View.VISIBLE
        Log.d(TAG, "Set popover view visibility to VISIBLE")

        // Bring to front
        popoverView?.bringToFront()
        Log.d(TAG, "Brought popover view to front")

        // Set high elevation to ensure it's on top
        popoverView?.elevation = 1000f
        Log.d(TAG, "Set popover view elevation to 1000f")

        // Set minimum size to ensure visibility
        popoverView?.minimumWidth = 200
        popoverView?.minimumHeight = 100
        Log.d(TAG, "Set popover view minimum size: 200x100")

        // Add entrance animation
        showPopoverWithAnimation()
    }

    private fun setupPeerPopoverContent(view: View, data: PopoverData) {
        val titleView = view.findViewById<TextView>(R.id.popoverTitle)
        val contentView = view.findViewById<TextView>(R.id.popoverContent)

        // Parse content (format: "name|line1|line2|...")
        val lines = data.content.split("|")
        if (lines.isNotEmpty()) {
            titleView.text = lines[0]
            if (lines.size > 1) {
                contentView.text = lines.drop(1).joinToString("\n")
            }
        }
    }

    private fun setupPoiPopoverContent(view: View, data: PopoverData) {
        val titleView = view.findViewById<TextView>(R.id.popoverTitle)
        val contentView = view.findViewById<TextView>(R.id.popoverContent)

        // Parse content
        val lines = data.content.split("|")
        if (lines.isNotEmpty()) {
            titleView.text = lines[0]
            if (lines.size > 1) {
                contentView.text = lines.drop(1).joinToString("\n")
            }
        }
    }

    private fun setupPolygonPopoverContent(view: View, data: PopoverData) {
        val titleView = view.findViewById<TextView>(R.id.popoverTitle)
        val contentView = view.findViewById<TextView>(R.id.popoverContent)

        // Parse content
        val lines = data.content.split("|")
        if (lines.isNotEmpty()) {
            titleView.text = lines[0]
            if (lines.size > 1) {
                contentView.text = lines.drop(1).joinToString("\n")
            }
        }
    }

    private fun positionPopoverView(position: LatLng) {
        Log.d(TAG, "positionPopoverView: positioning for latLng=$position")
        popoverView ?: return

        // Convert world coordinates to screen coordinates
        val screenPoint = mapLibreMap.projection.toScreenLocation(position)
        Log.d(TAG, "positionPopoverView: screen point=$screenPoint")
        
        // Get screen dimensions
        val screenWidth = rootView.width
        val screenHeight = rootView.height
        
        // Use post to ensure view is measured
        popoverView!!.post {
            Log.d(TAG, "Positioning popover view in post callback")
            
            // Calculate view position (above the point)
            val viewWidth = popoverView!!.width
            val viewHeight = popoverView!!.height
            
            Log.d(TAG, "View dimensions: width=$viewWidth, height=$viewHeight")
            
            // Calculate optimal position
            var x = screenPoint.x - (viewWidth / 2)
            var y = screenPoint.y - viewHeight - 20 // 20px above point
            
            // Ensure popover stays within screen bounds
            if (x < 0) x = 16f
            if (x + viewWidth > screenWidth) x = screenWidth - viewWidth - 16f
            if (y < 0) y = screenPoint.y + 20f // Show below if not enough space above
            
            // Set position using translation
            popoverView!!.translationX = x
            popoverView!!.translationY = y
            
            Log.d(TAG, "Positioned popover at screen coordinates: ($x, $y)")
            Log.d(TAG, "Popover view visibility: ${popoverView!!.visibility}")
            Log.d(TAG, "Popover view alpha: ${popoverView!!.alpha}")
        }
    }

    fun hideCurrentPopover() {
        Log.d(TAG, "Hiding current popover")
        currentPopover = null
        popoverDismissHandler.removeCallbacksAndMessages(null)
        
        // Remove custom view with animation
        hidePopoverWithAnimation()
        
        // Clear GL layer
        mapLibreMap.getStyle { style ->
            val source = style.getSourceAs<GeoJsonSource>(POPOVER_SOURCE)
            source?.setGeoJson(FeatureCollection.fromFeatures(arrayOf()))
        }
    }

    fun hasVisiblePopover(): Boolean = currentPopover != null

    // Update popover position when map moves
    fun updatePopoverPosition() {
        currentPopover?.let { data ->
            positionPopoverView(data.position)
        }
    }

    private fun showPopoverWithAnimation() {
        Log.d(TAG, "showPopoverWithAnimation: starting animation")
        popoverView?.alpha = 0f
        popoverView?.scaleX = 0.8f
        popoverView?.scaleY = 0.8f
        
        Log.d(TAG, "showPopoverWithAnimation: initial state - alpha=${popoverView?.alpha}, scaleX=${popoverView?.scaleX}, scaleY=${popoverView?.scaleY}")
        
        popoverView?.animate()
            ?.alpha(1f)
            ?.scaleX(1f)
            ?.scaleY(1f)
            ?.setDuration(200)
            ?.setInterpolator(android.view.animation.DecelerateInterpolator())
            ?.withEndAction {
                Log.d(TAG, "showPopoverWithAnimation: animation completed")
            }
            ?.start()
    }

    private fun hidePopoverWithAnimation() {
        popoverView?.animate()
            ?.alpha(0f)
            ?.scaleX(0.8f)
            ?.scaleY(0.8f)
            ?.setDuration(150)
            ?.setInterpolator(android.view.animation.AccelerateInterpolator())
            ?.withEndAction {
                popoverView?.let { rootView.removeView(it) }
                popoverView = null
            }
            ?.start()
    }

    private fun scheduleAutoDismiss(dismissTime: Long) {
        val delay = dismissTime - System.currentTimeMillis()
        if (delay > 0) {
            popoverDismissHandler.postDelayed({
                hideCurrentPopover()
            }, delay)
        }
    }

    private fun buildPeerPopoverContent(peerId: String, peerName: String?, lastHeard: Long?): String {
        val lines = mutableListOf<String>()
        
        // Title line
        val displayName = peerName ?: peerId
        lines.add(displayName)
        
        // Content lines
        if (lastHeard != null) {
            val ageSec = (System.currentTimeMillis() / 1000) - lastHeard
            val ageStr = if (ageSec > 60) "${ageSec / 60}m ago" else "${ageSec}s ago"
            lines.add(ageStr)
        }
        
        // Add coordinates and distance if available
        val peerLocation = meshNetworkViewModel.peerLocations.value[peerId]
        if (peerLocation != null) {
            val coords = String.format("%.5f, %.5f", peerLocation.latitude, peerLocation.longitude)
            lines.add(coords)
            
            // Add distance if user location available
            val userLocation = meshNetworkViewModel.bestLocation.value
            if (userLocation != null) {
                val distMeters = haversine(peerLocation.latitude, peerLocation.longitude, userLocation.latitude, userLocation.longitude)
                val distMiles = distMeters / 1609.344
                lines.add("${String.format("%.1f", distMiles)} mi away")
            }
        }
        
        return lines.joinToString("|")
    }

    private fun buildPoiPopoverContent(poi: MapAnnotation.PointOfInterest): String {
        val lines = mutableListOf<String>()
        
        // Title line - only add if label exists
        poi.label?.let { lines.add(it) }
        
        // Content lines
        val ageSec = (System.currentTimeMillis() - poi.timestamp) / 1000
        val ageStr = if (ageSec > 60) "${ageSec / 60}m old" else "${ageSec}s old"
        lines.add(ageStr)
        
        val coords = String.format("%.5f, %.5f", poi.position.lt, poi.position.lng)
        lines.add(coords)
        
        // Add distance if user location available
        val userLocation = meshNetworkViewModel.bestLocation.value
        if (userLocation != null) {
            val distMeters = haversine(poi.position.lt, poi.position.lng, userLocation.latitude, userLocation.longitude)
            val distMiles = distMeters / 1609.344
            lines.add("${String.format("%.1f", distMiles)} mi away")
        }
        
        return lines.joinToString("|")
    }

    private fun buildPolygonPopoverContent(polygon: MapAnnotation.Polygon): String {
        val lines = mutableListOf<String>()
        
        // Title line - polygon label or default name
        val title = polygon.label ?: "Polygon"
        lines.add(title)
        
        // Calculate area in square miles
        val areaSqMiles = calculatePolygonArea(polygon.points)
        lines.add("${String.format("%.2f", areaSqMiles)} sq mi")
        
        // Content lines
        val ageSec = (System.currentTimeMillis() - polygon.timestamp) / 1000
        val ageStr = if (ageSec > 60) "${ageSec / 60}m old" else "${ageSec}s old"
        lines.add(ageStr)
        
        val coords = String.format("%.5f, %.5f", polygon.points.map { it.lt }.average(), polygon.points.map { it.lng }.average())
        lines.add(coords)
        
        // Add distance if user location available
        val userLocation = meshNetworkViewModel.bestLocation.value
        if (userLocation != null) {
            val distMeters = haversine(polygon.points.map { it.lt }.average(), polygon.points.map { it.lng }.average(), userLocation.latitude, userLocation.longitude)
            val distMiles = distMeters / 1609.344
            lines.add("${String.format("%.1f", distMiles)} mi away")
        }
        
        return lines.joinToString("|")
    }

    private fun calculatePolygonArea(points: List<com.tak.lite.model.LatLngSerializable>): Double {
        if (points.size < 3) return 0.0
        
        // Use the Shoelace formula to calculate area
        var area = 0.0
        for (i in points.indices) {
            val j = (i + 1) % points.size
            area += points[i].lng * points[j].lt
            area -= points[j].lng * points[i].lt
        }
        area = Math.abs(area) / 2.0
        
        // Convert to square miles (approximate)
        // 1 degree latitude ≈ 69 miles
        // 1 degree longitude ≈ 69 * cos(latitude) miles
        val avgLat = points.map { it.lt }.average()
        val latMilesPerDegree = 69.0
        val lngMilesPerDegree = 69.0 * Math.cos(Math.toRadians(avgLat))
        
        val areaSqMiles = area * latMilesPerDegree * lngMilesPerDegree
        return areaSqMiles
    }
} 