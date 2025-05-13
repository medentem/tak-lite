package com.tak.lite.util

import org.maplibre.android.geometry.LatLng
import kotlin.math.*

object OsmTileUtils {

    private fun latLngToTileXY(lat: Double, lon: Double, zoom: Int): Pair<Int, Int> {
        val latRad = Math.toRadians(lat)
        val n = 2.0.pow(zoom.toDouble())
        val x = ((lon + 180.0) / 360.0 * n).toInt()
        val y = ((1.0 - ln(tan(latRad) + 1 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return Pair(x, y)
    }

    fun getTileRange(sw: LatLng, ne: LatLng, zoom: Int): List<Pair<Int, Int>> {
        val (x0, y0) = latLngToTileXY(sw.latitude, sw.longitude, zoom)
        val (x1, y1) = latLngToTileXY(ne.latitude, ne.longitude, zoom)
        val xMin = min(x0, x1)
        val xMax = max(x0, x1)
        val yMin = min(y0, y1)
        val yMax = max(y0, y1)
        val xs = xMin..xMax
        val ys = yMin..yMax
        return xs.flatMap { x -> ys.map { y -> Pair(x, y) } }
    }
} 