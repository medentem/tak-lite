package com.tak.lite.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.io.File
import com.tak.lite.util.saveTileWebpWithType
import kotlinx.coroutines.Dispatchers

class MapController(
    private val context: Context,
    private val mapView: MapView,
    private val defaultCenter: LatLng,
    private val defaultZoom: Double,
    private val onMapReady: (MapLibreMap) -> Unit = {},
    private val getMapTilerUrl: () -> String = { "" },
    private val getGlyphsUrl: () -> String = { "" },
    private val getHillshadingTileUrl: () -> String = { "" },
    private val getVectorTileUrl: () -> String = { "" },
    private val getVectorTileJsonUrl: () -> String = { "" },
    private val getMapTilerAttribution: () -> String = { "" },
    private val getOsmAttribution: () -> String = { "" },
    private val getFilesDir: () -> File = { File("") },
    private val getDarkModeMapTilerUrl: () -> String = { "" },
    private val getDarkModePref: () -> String = { "system" }
) {
    var mapLibreMap: MapLibreMap? = null
        private set
    private var mapType: MapType = MapType.HYBRID
    private var is3DEnabled: Boolean = false

    private var onStyleChanged: (() -> Unit)? = null
    private var onMapTypeChanged: ((MapType) -> Unit)? = null
    private var onMapReadyCallback: ((MapLibreMap) -> Unit)? = null
    private var onCameraMove: ((MapLibreMap) -> Unit)? = null

    private var isLocationComponentActivated = false
    private var pendingLocation: android.location.Location? = null

    fun setOnStyleChangedCallback(callback: (() -> Unit)?) {
        this.onStyleChanged = callback
    }

    fun setOnMapTypeChangedCallback(callback: ((MapType) -> Unit)?) {
        this.onMapTypeChanged = callback
    }
    
    fun setOnMapReadyCallback(callback: ((MapLibreMap) -> Unit)?) {
        this.onMapReadyCallback = callback
    }
    
    fun setOnCameraMoveListener(callback: ((MapLibreMap) -> Unit)?) {
        this.onCameraMove = callback
    }

    // Allow external callers to re-fire the style changed callback so dynamic overlays can restore ordering
    fun refireStyleChanged() {
        onStyleChanged?.invoke()
    }

    enum class MapType {
        LAST_USED, STREETS, SATELLITE, HYBRID
    }

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
            // Set the style based on the current map type first
            setStyleForCurrentViewport(map)
            // Initialize location component after style is set, but only if we have permissions
            if (androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
                androidx.core.app.ActivityCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                map.style?.let { style ->
                    val locationComponent = map.locationComponent
                    locationComponent.activateLocationComponent(
                        org.maplibre.android.location.LocationComponentActivationOptions.builder(context, style).build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                    locationComponent.renderMode = org.maplibre.android.location.modes.RenderMode.COMPASS
                    isLocationComponentActivated = true
                    // Apply any pending location
                    pendingLocation?.let {
                        locationComponent.forceLocationUpdate(it)
                        pendingLocation = null
                    }
                }
            }
            onMapReady(map)
            this.onMapReadyCallback?.invoke(map)
        }
    }

    private fun setStyleForCurrentViewport(map: MapLibreMap) {
        val tileCoords = getVisibleTileCoords(map)
        val useOffline = allOfflineTilesExist(tileCoords)
        val isDeviceOnline = isOnline()
        val hillshadingTileUrl = getHillshadingTileUrl()
        val mapTilerUrl = getMapTilerUrl()
        val mapTilerVectorJsonUrl = getVectorTileJsonUrl()
        val glyphsUrl = getGlyphsUrl();
        val mapTilerAttribution = getMapTilerAttribution()
        val osmAttribution = getOsmAttribution()
        val osmAttributionLine = if (osmAttribution.isNotBlank()) ",\n          \"attribution\": \"$osmAttribution\"" else ""
        val mapTilerAttributionLine = if (mapTilerAttribution.isNotBlank()) ",\n          \"attribution\": \"$mapTilerAttribution\"" else ""
        val darkModePref = getDarkModePref()
        val isDarkTheme = darkModePref == "dark" || (darkModePref == "system" && (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
        val darkModeTileUrl = getDarkModeMapTilerUrl()
        android.util.Log.d("MapController", "darkModePref: $darkModePref, isDarkTheme: $isDarkTheme, mapType: $mapType, darkModeTileUrl: $darkModeTileUrl")
        val styleJson = when {
            isDeviceOnline && mapType == MapType.SATELLITE && is3DEnabled -> {
                """
                {
                  "version": 8,
                  "glyphs": "$glyphsUrl",
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["$mapTilerUrl"],
                      "tileSize": 256$mapTilerAttributionLine
                    },
                    "vector-tiles": {
                      "type": "vector",
                      "url": "$mapTilerVectorJsonUrl"
                    },
                    "terrain-dem": {
                      "type": "raster-dem",
                      "tiles": ["$hillshadingTileUrl"],
                      "tileSize": 256$mapTilerAttributionLine
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    },
                    {
                      "id": "terrain-dem",
                      "type": "hillshade",
                      "source": "terrain-dem"
                    },
                    {
                      "id": "3d-buildings",
                      "type": "fill-extrusion",
                      "source": "vector-tiles",
                      "source-layer": "building",
                      "minzoom": 15,
                      "filter": ["!=", ["get", "hide_3d"], true],
                      "paint": {
                        "fill-extrusion-color": [
                          "interpolate", ["linear"], ["get", "render_height"],
                          0, "lightgray", 200, "royalblue", 400, "lightblue"
                        ],
                        "fill-extrusion-height": [
                          "interpolate", ["linear"], ["zoom"],
                          15, 0, 16, ["get", "render_height"]
                        ],
                        "fill-extrusion-base": ["case", [">=", ["get", "zoom"], 16], ["get", "render_min_height"], 0]
                      }
                    }
                  ],
                  "terrain": {
                    "source": "terrain-dem"
                  }
                }
                """
            }
            isDeviceOnline && mapType == MapType.HYBRID && is3DEnabled -> {
                """
                {
                  "version": 8,
                  "glyphs": "$glyphsUrl",
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["$mapTilerUrl"],
                      "tileSize": 256$mapTilerAttributionLine
                    },
                    "vector-tiles": {
                      "type": "vector",
                      "url": "$mapTilerVectorJsonUrl"
                    },
                    "terrain-dem": {
                      "type": "raster-dem",
                      "tiles": ["$hillshadingTileUrl"],
                      "tileSize": 256$mapTilerAttributionLine
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    },
                    {
                      "id": "terrain-dem",
                      "type": "hillshade",
                      "source": "terrain-dem"
                    },
                    {
                      "id": "3d-buildings",
                      "type": "fill-extrusion",
                      "source": "vector-tiles",
                      "source-layer": "building",
                      "minzoom": 15,
                      "filter": ["!=", ["get", "hide_3d"], true],
                      "paint": {
                        "fill-extrusion-color": [
                          "interpolate", ["linear"], ["get", "render_height"],
                          0, "lightgray", 200, "royalblue", 400, "lightblue"
                        ],
                        "fill-extrusion-height": [
                          "interpolate", ["linear"], ["zoom"],
                          15, 0, 16, ["get", "render_height"]
                        ],
                        "fill-extrusion-base": ["case", [">=", ["get", "zoom"], 16], ["get", "render_min_height"], 0]
                      }
                    },
                    {
                      "id": "road-minor",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "minor", "service"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.6)",
                        "line-width": 2.5
                      }
                    },
                    {
                      "id": "road-tertiary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "tertiary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.7)",
                        "line-width": 3
                      }
                    },
                    {
                      "id": "road-secondary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "secondary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.8)",
                        "line-width": 4
                      }
                    },
                    {
                      "id": "road-primary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "primary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.9)",
                        "line-width": 5
                      }
                    },
                    {
                      "id": "road-highway",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "motorway", "trunk"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 1)",
                        "line-width": 6
                      }
                    },
                    {
                      "id": "road-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "transportation_name",
                      "layout": {
                        "text-field": ["get", "name"],
                        "text-size": 12,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "rgba(255, 255, 255, 0.9)",
                        "text-halo-color": "rgba(0, 0, 0, 0.8)",
                        "text-halo-width": 1
                      }
                    },
                    {
                      "id": "place-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "place",
                      "layout": {
                        "text-field": ["get", "name"],
                        "text-size": 14,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "#ffff00",
                        "text-halo-color": "#000000",
                        "text-halo-width": 2
                      }
                    },
                    {
                      "id": "housenumber-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "housenumber",
                      "layout": {
                        "text-field": ["get", "housenumber"],
                        "text-size": 10,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "#00ffff",
                        "text-halo-color": "#000000",
                        "text-halo-width": 1
                      }
                    }
                  ],
                  "terrain": {
                    "source": "terrain-dem"
                  }
                }
                """
            }
            isDeviceOnline && mapType == MapType.SATELLITE -> {
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
            isDeviceOnline && mapType == MapType.HYBRID -> {
                val style = """
                {
                  "version": 8,
                  "glyphs": "$glyphsUrl",
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["$mapTilerUrl"],
                      "tileSize": 512$mapTilerAttributionLine
                    },
                    "vector-tiles": {
                      "type": "vector",
                      "url": "$mapTilerVectorJsonUrl"
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    },
                    {
                      "id": "road-minor",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "minor", "service"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.6)",
                        "line-width": 2.5
                      }
                    },
                    {
                      "id": "road-tertiary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "tertiary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.7)",
                        "line-width": 3
                      }
                    },
                    {
                      "id": "road-secondary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "secondary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.8)",
                        "line-width": 4
                      }
                    },
                    {
                      "id": "road-primary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "primary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.9)",
                        "line-width": 5
                      }
                    },
                    {
                      "id": "road-highway",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "motorway", "trunk"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 1)",
                        "line-width": 6
                      }
                    },
                    {
                      "id": "road-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "transportation_name",
                      "layout": {
                        "text-field": ["get", "name"],
                        "text-size": 12,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "rgba(255, 255, 255, 0.9)",
                        "text-halo-color": "rgba(0, 0, 0, 0.8)",
                        "text-halo-width": 1
                      }
                    },
                    {
                      "id": "place-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "place",
                      "layout": {
                        "text-field": ["get", "name"],
                        "text-size": 14,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "#ffff00",
                        "text-halo-color": "#000000",
                        "text-halo-width": 2
                      }
                    },
                    {
                      "id": "housenumber-label",
                      "type": "symbol",
                      "source": "vector-tiles",
                      "source-layer": "housenumber",
                      "layout": {
                        "text-field": ["get", "housenumber"],
                        "text-size": 10,
                        "text-anchor": "center",
                        "text-allow-overlap": false
                      },
                      "paint": {
                        "text-color": "#00ffff",
                        "text-halo-color": "#000000",
                        "text-halo-width": 1
                      }
                    }
                  ]
                }
                """
                style
            }
            isDeviceOnline && mapType == MapType.STREETS && is3DEnabled -> {
                android.util.Log.d("MapController", "Using 3D buildings in STREETS mode (dark: $isDarkTheme)")
                val rasterTilesUrl = if (isDarkTheme) darkModeTileUrl else "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                val rasterAttributionLine = if (isDarkTheme) mapTilerAttributionLine else osmAttributionLine
                """
                {
                  "version": 8,
                  "glyphs": "$glyphsUrl",
                  "sources": {
                    "vector-tiles": {
                      "type": "vector",
                      "url": "$mapTilerVectorJsonUrl"
                    },
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["$rasterTilesUrl"],
                      "tileSize": 256$rasterAttributionLine
                    }
                  },
                  "layers": [
                    {
                      "id": "raster-tiles",
                      "type": "raster",
                      "source": "raster-tiles"
                    },
                    {
                      "id": "3d-buildings",
                      "type": "fill-extrusion",
                      "source": "vector-tiles",
                      "source-layer": "building",
                      "minzoom": 15,
                      "filter": ["!=", ["get", "hide_3d"], true],
                      "paint": {
                        "fill-extrusion-color": [
                          "interpolate", ["linear"], ["get", "render_height"],
                          0, "lightgray", 200, "royalblue", 400, "lightblue"
                        ],
                        "fill-extrusion-height": [
                          "interpolate", ["linear"], ["zoom"],
                          15, 0, 16, ["get", "render_height"]
                        ],
                        "fill-extrusion-base": ["case", [">=", ["get", "zoom"], 16], ["get", "render_min_height"], 0]
                      }
                    }
                  ]
                }
                """
            }
            isDeviceOnline && mapType == MapType.STREETS -> {
                val rasterTilesUrl = if (isDarkTheme) darkModeTileUrl else "https://tile.openstreetmap.org/{z}/{x}/{y}.png"
                val rasterAttributionLine = if (isDarkTheme) mapTilerAttributionLine else osmAttributionLine
                """
                {
                  "version": 8,
                  "sources": {
                    "raster-tiles": {
                      "type": "raster",
                      "tiles": ["$rasterTilesUrl"],
                      "tileSize": 256$rasterAttributionLine
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
            useOffline && mapType == MapType.SATELLITE -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["file://${getFilesDir()}/tiles/satellite-v2/{z}/{x}/{y}.png"],
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
            useOffline && mapType == MapType.HYBRID -> {
                """
                {
                  "version": 8,
                  "sources": {
                    "satellite-tiles": {
                      "type": "raster",
                      "tiles": ["file://${getFilesDir()}/tiles/satellite-v2/{z}/{x}/{y}.png"],
                      "tileSize": 256
                    },
                    "vector-tiles": {
                      "type": "vector",
                      "tiles": ["file://${getFilesDir()}/tiles/vector/{z}/{x}/{y}.pbf"],
                      "tileSize": 512
                    }
                  },
                  "layers": [
                    {
                      "id": "satellite-tiles",
                      "type": "raster",
                      "source": "satellite-tiles"
                    },
                    {
                      "id": "road-minor",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "minor", "service"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.6)",
                        "line-width": 1.5
                      }
                    },
                    {
                      "id": "road-tertiary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "tertiary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.7)",
                        "line-width": 2
                      }
                    },
                    {
                      "id": "road-secondary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "secondary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.8)",
                        "line-width": 3
                      }
                    },
                    {
                      "id": "road-primary",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["==", "class", "primary"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 0.9)",
                        "line-width": 4
                      }
                    },
                    {
                      "id": "road-highway",
                      "type": "line",
                      "source": "vector-tiles",
                      "source-layer": "transportation",
                      "filter": ["all", ["in", "class", "motorway", "trunk"]],
                      "paint": {
                        "line-color": "rgba(255, 255, 255, 1)",
                        "line-width": 5
                      }
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
            onStyleChanged?.invoke()
            // Force a redraw of the annotation overlay
            map.addOnCameraMoveListener {
                onStyleChanged?.invoke()
                onCameraMove?.invoke(map)
            }
        }
    }

    fun toggleMapType() {
        mapType = when (mapType) {
            MapType.STREETS -> MapType.SATELLITE
            MapType.SATELLITE -> MapType.HYBRID
            MapType.HYBRID, MapType.LAST_USED -> MapType.STREETS // Never set to LAST_USED
        }
        onMapTypeChanged?.invoke(mapType)
        mapLibreMap?.let { setStyleForCurrentViewport(it) }
    }

    fun setMapType(type: MapType) {
        mapType = type
        onMapTypeChanged?.invoke(mapType)
        mapLibreMap?.let { setStyleForCurrentViewport(it) }
    }

    fun getMapType(): MapType = mapType

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
        android.util.Log.d("OfflineTiles", "Checking offline tiles existence for ${tileCoords.size} tiles")
        for ((z, x, y) in tileCoords) {
            val osmFile = File(getFilesDir(), "tiles/osm/$z/$x/$y.png")
            val satFile = File(getFilesDir(), "tiles/satellite-v2/$z/$x/$y.png")
            val vectorFile = File(getFilesDir(), "tiles/vector/$z/$x/$y.pbf")
            val terrainFile = File(getFilesDir(), "tiles/terrain-dem/$z/$x/$y.webp")
            
            val osmExists = osmFile.exists()
            val satExists = satFile.exists()
            val vectorExists = vectorFile.exists()
            val terrainExists = terrainFile.exists()
            
            // Check required tiles based on zoom level
            val requiresVector = z <= 15
            val requiresTerrain = z <= 14
            
            val missingRequired = !osmExists || !satExists || 
                                (requiresVector && !vectorExists) || 
                                (requiresTerrain && !terrainExists)
            
            if (missingRequired) {
                android.util.Log.d("OfflineTiles", "Missing tiles for $z/$x/$y - OSM: $osmExists, SAT: $satExists, VECTOR: $vectorExists (required: $requiresVector), TERRAIN: $terrainExists (required: $requiresTerrain)")
                return false
            }
        }
        android.util.Log.d("OfflineTiles", "All offline tiles exist for ${tileCoords.size} tiles")
        return tileCoords.isNotEmpty()
    }

    fun onResume() { mapView.onResume() }
    fun onPause() { mapView.onPause() }
    fun onStart() { mapView.onStart() }
    fun onStop() { mapView.onStop() }
    fun onLowMemory() { mapView.onLowMemory() }
    fun onDestroy() { mapView.onDestroy() }

    suspend fun downloadVisibleTiles(onProgress: ((completed: Int, total: Int) -> Unit)? = null): Pair<Int, Int> {
        val map = mapLibreMap ?: return 0 to 0
        val projection = map.projection
        val visibleRegion = projection.visibleRegion
        val bounds = visibleRegion.latLngBounds
        val currentZoom = map.cameraPosition.zoom.toInt()
        val zoomLevels = listOf((currentZoom - 1).coerceAtLeast(1), currentZoom, (currentZoom + 1))
        var successCount = 0
        var failCount = 0
        val mapTilerUrl = getMapTilerUrl()
        val mapTilerVectorUrl = getVectorTileUrl()
        val hillshadingTileUrl = getHillshadingTileUrl()
        
        android.util.Log.d("OfflineTiles", "Starting tile download - bounds: $bounds, currentZoom: $currentZoom, zoomLevels: $zoomLevels")
        android.util.Log.d("OfflineTiles", "MapTiler URL: $mapTilerUrl")
        android.util.Log.d("OfflineTiles", "MapTiler Vector URL: $mapTilerVectorUrl")
        android.util.Log.d("OfflineTiles", "Hillshading URL: $hillshadingTileUrl")
        
        // Calculate total number of tile downloads (OSM + satellite + vector + terrain-dem for each tile)
        var totalTiles = 0
        val tileCoordsByZoom = zoomLevels.associateWith { com.tak.lite.util.OsmTileUtils.getTileRange(bounds.southWest, bounds.northEast, it) }
        tileCoordsByZoom.forEach { (zoom, tilePairs) ->
            totalTiles += tilePairs.size * 2 // OSM + satellite (always downloaded)
            if (zoom <= 14) totalTiles += tilePairs.size // terrain-dem (only for zoom <= 14)
            if (zoom <= 15) totalTiles += tilePairs.size // vector (only for zoom <= 15)
            android.util.Log.d("OfflineTiles", "Zoom $zoom: ${tilePairs.size} tiles")
        }
        android.util.Log.d("OfflineTiles", "Total tiles to download: $totalTiles")
        
        var completedTiles = 0
        for (zoom in zoomLevels) {
            val tilePairs = tileCoordsByZoom[zoom] ?: continue
            android.util.Log.d("OfflineTiles", "Processing zoom level $zoom with ${tilePairs.size} tiles")
            
            for ((x, y) in tilePairs) {
                android.util.Log.d("OfflineTiles", "Processing tile $zoom/$x/$y")
                
                // Download OSM tile
                val osmUrl = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
                android.util.Log.d("OfflineTiles", "Downloading OSM tile: $osmUrl")
                try {
                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val connection = java.net.URL(osmUrl).openConnection() as java.net.HttpURLConnection
                        connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        val responseCode = connection.responseCode
                        android.util.Log.d("OfflineTiles", "OSM tile $zoom/$x/$y response code: $responseCode")
                        if (responseCode != 200) {
                            android.util.Log.e("OfflineTiles", "OSM tile $zoom/$x/$y failed with response code: $responseCode")
                            throw Exception("HTTP $responseCode")
                        }
                        connection.inputStream.use { it.readBytes() }
                    }
                    android.util.Log.d("OfflineTiles", "OSM tile $zoom/$x/$y downloaded successfully, size: ${bytes.size} bytes")
                    val saved = com.tak.lite.util.saveTilePngWithType(context, "osm", zoom, x, y, bytes)
                    if (saved) {
                        successCount++
                        android.util.Log.d("OfflineTiles", "OSM tile $zoom/$x/$y saved successfully")
                    } else {
                        failCount++
                        android.util.Log.e("OfflineTiles", "Failed to save OSM tile $zoom/$x/$y")
                    }
                } catch (e: Exception) {
                    failCount++
                    android.util.Log.e("OfflineTiles", "Exception downloading OSM tile $zoom/$x/$y from $osmUrl: ${e.message}", e)
                }
                completedTiles++
                onProgress?.invoke(completedTiles, totalTiles)
                
                // Download satellite tile
                val satUrl = mapTilerUrl
                    .replace("{z}", zoom.toString())
                    .replace("{x}", x.toString())
                    .replace("{y}", y.toString())
                android.util.Log.d("OfflineTiles", "Downloading satellite tile: $satUrl")
                try {
                    val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val connection = java.net.URL(satUrl).openConnection() as java.net.HttpURLConnection
                        connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        val responseCode = connection.responseCode
                        android.util.Log.d("OfflineTiles", "Satellite tile $zoom/$x/$y response code: $responseCode")
                        if (responseCode != 200) {
                            android.util.Log.e("OfflineTiles", "Satellite tile $zoom/$x/$y failed with response code: $responseCode")
                            throw Exception("HTTP $responseCode")
                        }
                        connection.inputStream.use { it.readBytes() }
                    }
                    android.util.Log.d("OfflineTiles", "Satellite tile $zoom/$x/$y downloaded successfully, size: ${bytes.size} bytes")
                    val saved = com.tak.lite.util.saveTilePngWithType(context, "satellite-v2", zoom, x, y, bytes)
                    if (saved) {
                        successCount++
                        android.util.Log.d("OfflineTiles", "Satellite tile $zoom/$x/$y saved successfully")
                    } else {
                        failCount++
                        android.util.Log.e("OfflineTiles", "Failed to save satellite tile $zoom/$x/$y")
                    }
                } catch (e: Exception) {
                    failCount++
                    android.util.Log.e("OfflineTiles", "Exception downloading satellite tile $zoom/$x/$y from $satUrl: ${e.message}", e)
                }
                completedTiles++
                onProgress?.invoke(completedTiles, totalTiles)
                
                // Download terrain-dem tile (only for zoom levels <= 14 due to MapTiler service limitations)
                if (zoom <= 14) {
                    val terrainUrl = hillshadingTileUrl
                        .replace("{z}", zoom.toString())
                        .replace("{x}", x.toString())
                        .replace("{y}", y.toString())
                    android.util.Log.d("OfflineTiles", "Downloading terrain tile: $terrainUrl")
                    try {
                        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection = java.net.URL(terrainUrl).openConnection() as java.net.HttpURLConnection
                            connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            val responseCode = connection.responseCode
                            android.util.Log.d("OfflineTiles", "Terrain tile $zoom/$x/$y response code: $responseCode")
                            if (responseCode != 200) {
                                android.util.Log.e("OfflineTiles", "Terrain tile $zoom/$x/$y failed with response code: $responseCode")
                                throw Exception("HTTP $responseCode")
                            }
                            connection.inputStream.use { it.readBytes() }
                        }
                        android.util.Log.d("OfflineTiles", "Terrain tile $zoom/$x/$y downloaded successfully, size: ${bytes.size} bytes")
                        val saved = com.tak.lite.util.saveTileWebpWithType(context, "terrain-dem", zoom, x, y, bytes)
                        if (saved) {
                            successCount++
                            android.util.Log.d("OfflineTiles", "Terrain tile $zoom/$x/$y saved successfully")
                        } else {
                            failCount++
                            android.util.Log.e("OfflineTiles", "Failed to save terrain tile $zoom/$x/$y")
                        }
                    } catch (e: Exception) {
                        failCount++
                        android.util.Log.e("OfflineTiles", "Exception downloading terrain tile $zoom/$x/$y from $terrainUrl: ${e.message}", e)
                    }
                } else {
                    android.util.Log.d("OfflineTiles", "Skipping terrain tile download for zoom $zoom (MapTiler terrain service limited to zoom <= 14)")
                }
                completedTiles++
                onProgress?.invoke(completedTiles, totalTiles)
                
                // Download vector tile (only if zoom <= 15)
                if (zoom <= 15) {
                    val vectorUrl = mapTilerVectorUrl
                        .replace("{z}", zoom.toString())
                        .replace("{x}", x.toString())
                        .replace("{y}", y.toString())
                    android.util.Log.d("OfflineTiles", "Downloading vector tile: $vectorUrl")
                    try {
                        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val connection = java.net.URL(vectorUrl).openConnection() as java.net.HttpURLConnection
                            connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                            connection.connectTimeout = 10000
                            connection.readTimeout = 10000
                            val responseCode = connection.responseCode
                            android.util.Log.d("OfflineTiles", "Vector tile $zoom/$x/$y response code: $responseCode")
                            if (responseCode != 200) {
                                android.util.Log.e("OfflineTiles", "Vector tile $zoom/$x/$y failed with response code: $responseCode")
                                throw Exception("HTTP $responseCode")
                            }
                            connection.inputStream.use { it.readBytes() }
                        }
                        android.util.Log.d("OfflineTiles", "Vector tile $zoom/$x/$y downloaded successfully, size: ${bytes.size} bytes")
                        val saved = com.tak.lite.util.saveTilePbfWithType(context, "vector", zoom, x, y, bytes)
                        if (saved) {
                            successCount++
                            android.util.Log.d("OfflineTiles", "Vector tile $zoom/$x/$y saved successfully")
                        } else {
                            android.util.Log.e("OfflineTiles", "Failed to save vector tile $zoom/$x/$y from $vectorUrl (saveTilePbfWithType returned false)")
                            failCount++
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("OfflineTiles", "Exception downloading vector tile $zoom/$x/$y from $vectorUrl: ${e.message}", e)
                        failCount++
                    }
                    completedTiles++
                    onProgress?.invoke(completedTiles, totalTiles)
                }
            }
        }
        
        android.util.Log.d("OfflineTiles", "Download complete - Success: $successCount, Failed: $failCount, Total: $totalTiles")
        return successCount to failCount
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

            isLocationComponentActivated = true
            // Apply any pending location
            pendingLocation?.let {
                locationComponent.forceLocationUpdate(it)
                pendingLocation = null
            }

            // Add camera move listener to disable tracking when user pans
            map.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                    locationComponent.cameraMode = org.maplibre.android.location.modes.CameraMode.NONE
                }
            }
        }
    }

    fun set3DBuildingsEnabled(enabled: Boolean) {
        is3DEnabled = enabled
        mapLibreMap?.let {
            setStyleForCurrentViewport(it)
            // Set camera tilt for 3D buildings
            val currentPosition = it.cameraPosition
            val newTilt = if (enabled) 45.0 else 0.0
            val cameraPosition = org.maplibre.android.camera.CameraPosition.Builder()
                .target(currentPosition.target)
                .zoom(currentPosition.zoom)
                .tilt(newTilt)
                .bearing(currentPosition.bearing)
                .build()
            it.animateCamera(org.maplibre.android.camera.CameraUpdateFactory.newCameraPosition(cameraPosition))
        }
    }

    /**
     * Downloads a single terrain-dem tile (webp) for the given zoom/x/y. Returns true if successful.
     * Only downloads for zoom levels <= 14 due to MapTiler service limitations.
     */
    suspend fun downloadTerrainDemTile(zoom: Int, x: Int, y: Int): Boolean {
        // Skip terrain downloads for zoom levels > 14 due to MapTiler service limitations
        if (zoom > 14) {
            android.util.Log.d("OfflineTiles", "Skipping terrain-dem tile download for zoom $zoom (MapTiler terrain service limited to zoom <= 14)")
            return false
        }
        
        // Note: This method is called from UI components that don't have access to TerrainAnalyzer
        // The coordination with TerrainAnalyzer should be handled by the calling code (CoverageCalculator)
        // This method remains focused on the actual download operation
        
        val hillshadingTileUrl = getHillshadingTileUrl()
        val terrainUrl = hillshadingTileUrl
            .replace("{z}", zoom.toString())
            .replace("{x}", x.toString())
            .replace("{y}", y.toString())
        android.util.Log.d("OfflineTiles", "Downloading single terrain-dem tile: $terrainUrl")
        return try {
            val bytes = withContext(Dispatchers.IO) {
                val connection = java.net.URL(terrainUrl).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                val responseCode = connection.responseCode
                android.util.Log.d("OfflineTiles", "Terrain-dem tile $zoom/$x/$y response code: $responseCode")
                if (responseCode != 200) {
                    android.util.Log.e("OfflineTiles", "Terrain-dem tile $zoom/$x/$y failed with response code: $responseCode")
                    throw Exception("HTTP $responseCode")
                }
                connection.inputStream.use { it.readBytes() }
            }
            android.util.Log.d("OfflineTiles", "Terrain-dem tile $zoom/$x/$y downloaded successfully, size: ${bytes.size} bytes")
            val saved = saveTileWebpWithType(context, "terrain-dem", zoom, x, y, bytes)
            if (saved) {
                android.util.Log.d("OfflineTiles", "Terrain-dem tile $zoom/$x/$y saved successfully")
            } else {
                android.util.Log.e("OfflineTiles", "Failed to save terrain-dem tile $zoom/$x/$y")
            }
            saved
        } catch (e: Exception) {
            android.util.Log.e("OfflineTiles", "Failed to download terrain-dem tile $zoom/$x/$y: ${e.message}", e)
            false
        }
    }

    fun updateUserLocation(location: android.location.Location) {
        val map = mapLibreMap ?: return
        val locationComponent = map.locationComponent
        if (isLocationComponentActivated) {
            locationComponent.forceLocationUpdate(location)
        } else {
            pendingLocation = location
        }
    }
} 