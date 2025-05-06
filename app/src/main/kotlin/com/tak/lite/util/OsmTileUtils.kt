package com.tak.lite.util

import org.maplibre.android.geometry.LatLng
import kotlin.math.*

object OsmTileUtils {
    const val TILE_SIZE = 256

    fun latLngToTileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val latRad = Math.toRadians(lat)
        val n = 2.0.pow(zoom.toDouble())
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val y = ((1.0 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    fun tileXYToBounds(x: Int, y: Int, zoom: Int): Pair<LatLng, LatLng> {
        val n = 2.0.pow(zoom.toDouble())
        val lon1 = x / n * 360.0 - 180.0
        val lat1 = atan(sinh(PI * (1 - 2 * y / n))) * 180.0 / PI
        val lon2 = (x + 1) / n * 360.0 - 180.0
        val lat2 = atan(sinh(PI * (1 - 2 * (y + 1) / n))) * 180.0 / PI
        return Pair(LatLng(lat2, lon1), LatLng(lat1, lon2)) // SW, NE
    }

    fun getTileRange(sw: LatLng, ne: LatLng, zoom: Int): List<Pair<Int, Int>> {
        val (xMin, yMax) = latLngToTileXY(sw.latitude, sw.longitude, zoom)
        val (xMax, yMin) = latLngToTileXY(ne.latitude, ne.longitude, zoom)
        val xs = xMin..xMax
        val ys = yMin..yMax
        return xs.flatMap { x -> ys.map { y -> Pair(x, y) } }
    }
} 