package com.tak.lite.ui.map

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import com.tak.lite.databinding.ActivityMainBinding
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.VisibleRegion
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageButton
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.PointShape
import org.maplibre.android.annotations.Polygon
import org.maplibre.android.annotations.PolygonOptions
import java.io.File
import kotlinx.coroutines.withContext

class MapController(
    private val context: Context,
    private val mapView: MapView,
    private val binding: ActivityMainBinding,
    private val defaultCenter: LatLng,
    private val defaultZoom: Double,
    private val onMapReady: (MapLibreMap) -> Unit = {},
    private val onStyleChanged: (() -> Unit)? = null,
    private val getIsSatellite: () -> Boolean = { false },
    private val getMapTilerUrl: () -> String = { "" },
    private val getMapTilerAttribution: () -> String = { "" },
    private val getOsmAttribution: () -> String = { "" },
    private val getFilesDir: () -> File = { File("") }
) {
    var mapLibreMap: MapLibreMap? = null
        private set
    private var isSatellite: Boolean = false

    fun onCreate(savedInstanceState: Bundle?, lastLocation: Triple<Double, Double, Float>?) {
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync { map ->
            mapLibreMap = map
            if (lastLocation != null) {
                val (lat, lon, zoom) = lastLocation
                val initialZoom = (zoom - 2.0f).coerceAtLeast(1.0f)
                map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), initialZoom.toDouble()))
            } else {
                map.moveCamera(org.maplibre.android.camera.CameraUpdateFactory.newLatLngZoom(defaultCenter, defaultZoom))
            }
            setStyleForCurrentViewport(map)
            map.addOnCameraIdleListener {
                setStyleForCurrentViewport(map)
            }
            map.setStyle(org.maplibre.android.maps.Style.Builder().fromUri("asset://styles/style.json")) {
                setupLocationComponent(map)
            }
            onMapReady(map)
        }
    }

    fun setStyleForCurrentViewport(map: MapLibreMap) {
        val tileCoords = getVisibleTileCoords(map)
        val useOffline = allOfflineTilesExist(tileCoords)
        val isDeviceOnline = isOnline()
        val isSatellite = this.isSatellite
        val mapTilerUrl = getMapTilerUrl()
        val mapTilerAttribution = getMapTilerAttribution()
        val osmAttribution = getOsmAttribution()
        val osmAttributionLine = if (osmAttribution.isNotBlank()) ",\n          \"attribution\": \"$osmAttribution\"" else ""
        val mapTilerAttributionLine = if (mapTilerAttribution.isNotBlank()) ",\n          \"attribution\": \"$mapTilerAttribution\"" else ""
        val styleJson = when {
            isDeviceOnline && isSatellite -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["$mapTilerUrl"],
                      "tileSize": 256$mapTilerAttributionLine
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    }
                  ]
                }
                """
            }
            isDeviceOnline -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
                      "tileSize": 256$osmAttributionLine
                    }
                  },
                  "layers": [
                    {
                      "id": "raster-tiles",
                      "type": "raster",
                      "source": "raster-tiles"
                    }
                  ]
                }
                """
            }
            useOffline && isSatellite -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["file://${getFilesDir()}/tiles/satellite/{z}/{x}/{y}.png"],
                      "tileSize": 256
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    }
                  ]
                }
                """
            }
            useOffline -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["file://${getFilesDir()}/tiles/osm/{z}/{x}/{y}.png"],
                      "tileSize": 256
                    }
                  },
                  "layers": [
                    {
                      "id": "raster-tiles",
                      "type": "raster",
                      "source": "raster-tiles"
                    }
                  ]
                }
                """
            }
            else -> {
                Toast.makeText(context, "No map tiles available (offline tiles missing and no internet)", Toast.LENGTH_LONG).show()
                """
                {
                  "version": 8,
                  "sources": {},
                  "layers": []
                }
                """
            }
        }
        map.setStyle(org.maplibre.android.maps.Style.Builder().fromJson(styleJson)) {
            setupLocationComponent(map)
            onStyleChanged?.invoke()
        }
    }

    fun toggleMapType() {
        isSatellite = !isSatellite
        mapLibreMap?.let { setStyleForCurrentViewport(it) }
    }

    fun getIsSatelliteMode(): Boolean = isSatellite

    private fun isOnline(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getVisibleTileCoords(map: MapLibreMap): List<Triple<Int, Int, Int>> {
        val projection = map.projection
        val visibleRegion = projection.visibleRegion
        val bounds = visibleRegion.latLngBounds
        val zoom = map.cameraPosition.zoom.toInt()
        return com.tak.lite.util.OsmTileUtils.getTileRange(bounds.southWest, bounds.northEast, zoom).map { (x, y) ->
            Triple(zoom, x, y)
        }
    }

    private fun allOfflineTilesExist(tileCoords: List<Triple<Int, Int, Int>>): Boolean {
        for ((z, x, y) in tileCoords) {
            val osmFile = File(getFilesDir(), "tiles/osm/$z/$x/$y.png")
            val satFile = File(getFilesDir(), "tiles/satellite/$z/$x/$y.png")
            if (!osmFile.exists() || !satFile.exists()) return false
        }
        return tileCoords.isNotEmpty()
    }

    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onStart() { mapView.onStart() }
    fun onStop() { mapView.onStop() }
    fun onLowMemory() { mapView.onLowMemory() }
    fun onDestroy() { mapView.onDestroy() }

    suspend fun downloadVisibleTiles(): Pair<Int, Int> {
        val map = mapLibreMap ?: return 0 to 0
        val projection = map.projection
        val visibleRegion = projection.visibleRegion
        val bounds = visibleRegion.latLngBounds
        val currentZoom = map.cameraPosition.zoom.toInt()
        val zoomLevels = listOf((currentZoom - 1).coerceAtLeast(1), currentZoom, (currentZoom + 1))
        var successCount = 0
        var failCount = 0
        val mapTilerUrl = getMapTilerUrl()
        for (zoom in zoomLevels) {
            val tilePairs = com.tak.lite.util.OsmTileUtils.getTileRange(bounds.southWest, bounds.northEast, zoom)
            for ((x, y) in tilePairs) {
                // Download OSM tile
                val osmUrl = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                try {
                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val connection = java.net.URL(osmUrl).openConnection() as java.net.HttpURLConnection
                        connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                        connection.inputStream.use { it.readBytes() }
                    }
                    val saved = com.tak.lite.util.saveTilePngWithType(context, "osm", zoom, x, y, bytes)
                    if (saved) successCount++ else failCount++
                } catch (e: Exception) {
                    failCount++
                }
                // Download satellite tile
                val satUrl = mapTilerUrl
                    .replace("{z}", zoom.toString())
                    .replace("{x}", x.toString())
                    .replace("{y}", y.toString())
                try {
                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val connection = java.net.URL(satUrl).openConnection() as java.net.HttpURLConnection
                        connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                        connection.inputStream.use { it.readBytes() }
                    }
                    val saved = com.tak.lite.util.saveTilePngWithType(context, "satellite", zoom, x, y, bytes)
                    if (saved) successCount++ else failCount++
                } catch (e: Exception) {
                    failCount++
                }
            }
        }
        return successCount to failCount
    }

    fun setMapTouchEnabled(enabled: Boolean) {
        mapLibreMap?.let { map ->
            map.uiSettings.apply {
                isScrollGesturesEnabled = enabled
                isRotateGesturesEnabled = enabled
                isTiltGesturesEnabled = enabled
                isZoomGesturesEnabled = enabled
            }
        }
    }

    private fun setupLocationComponent(map: MapLibreMap) {
        if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            val locationComponent = map.locationComponent
            locationComponent.activateLocationComponent(
                org.maplibre.android.location.LocationComponentActivationOptions.builder(context, map.style!!).build()
            )
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
            locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS

            // Add camera move listener to disable tracking when user pans
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == org.maplibre.android.maps.MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                }
            }
        }
    }

    fun enableLocationTracking() {
        mapLibreMap?.let { map ->
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                map.locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.TRACKING
            }
        }
    }

    fun disableLocationTracking() {
        mapLibreMap?.let { map ->
            map.locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
        }
    }
} 