package com.tak.lite.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import android.graphics.BitmapFactory
import kotlin.math.*

suspend fun saveTilePngWithType(context: Context, type: String, zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "tiles/$type/$zoom/$x")
        android.util.Log.d("OfflineTiles", "Creating directory: ${dir.absolutePath}")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.d("OfflineTiles", "Directory creation result: $created")
        }
        val file = File(dir, "$y.png")
        android.util.Log.d("OfflineTiles", "Saving $type tile to: ${file.absolutePath}, size: ${bytes.size} bytes")
        FileOutputStream(file).use { it.write(bytes) }
        val savedSize = file.length()
        android.util.Log.d("OfflineTiles", "Successfully saved $type tile $zoom/$x/$y, file size: $savedSize bytes")
        true
    } catch (e: Exception) {
        android.util.Log.e("OfflineTiles", "Failed to save $type tile $zoom/$x/$y: ${e.message}", e)
        false
    }
}

suspend fun saveTilePbfWithType(context: Context, type: String, zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "tiles/$type/$zoom/$x")
        android.util.Log.d("OfflineTiles", "Creating directory: ${dir.absolutePath}")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.d("OfflineTiles", "Directory creation result: $created")
        }
        val file = File(dir, "$y.pbf")
        android.util.Log.d("OfflineTiles", "Saving $type tile to: ${file.absolutePath}, size: ${bytes.size} bytes")
        FileOutputStream(file).use { it.write(bytes) }
        val savedSize = file.length()
        android.util.Log.d("OfflineTiles", "Successfully saved $type tile $zoom/$x/$y, file size: $savedSize bytes")
        true
    } catch (e: Exception) {
        android.util.Log.e("OfflineTiles", "Failed to save $type tile $zoom/$x/$y: ${e.message}", e)
        false
    }
}

suspend fun saveTileWebpWithType(context: Context, type: String, zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "tiles/$type/$zoom/$x")
        android.util.Log.d("OfflineTiles", "Creating directory: ${dir.absolutePath}")
        if (!dir.exists()) {
            val created = dir.mkdirs()
            android.util.Log.d("OfflineTiles", "Directory creation result: $created")
        }
        val file = File(dir, "$y.webp")
        android.util.Log.d("OfflineTiles", "Saving $type tile to: ${file.absolutePath}, size: ${bytes.size} bytes")
        FileOutputStream(file).use { it.write(bytes) }
        val savedSize = file.length()
        android.util.Log.d("OfflineTiles", "Successfully saved $type tile $zoom/$x/$y, file size: $savedSize bytes")
        true
    } catch (e: Exception) {
        android.util.Log.e("OfflineTiles", "Failed to save $type tile $zoom/$x/$y: ${e.message}", e)
        false
    }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6378137.0 // meters
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    return R * c
}

/**
 * Returns the elevation in meters for the given lat/lon at the specified zoom, using offline terrain-dem tiles.
 * Returns null if the tile is missing or unreadable.
 * Tiles must be in Mapbox/MapTiler terrain-rgb format (.webp).
 */
fun getOfflineElevation(lat: Double, lon: Double, zoom: Int, filesDir: File): Double? {
    // Compute tile x/y
    val n = 2.0.pow(zoom.toDouble())
    val x = ((lon + 180.0) / 360.0 * n).toInt()
    val latRad = Math.toRadians(lat)
    val y = ((1.0 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    val tileFile = File(filesDir, "tiles/terrain-dem/$zoom/$x/$y.webp")
    if (!tileFile.exists()) return null
    val bitmap = BitmapFactory.decodeFile(tileFile.absolutePath) ?: return null
    // Convert lat/lon to pixel in tile
    val tileSize = bitmap.width // Assume square
    val xTile = (lon + 180.0) / 360.0 * n
    val yTile = (1.0 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2.0 * n
    val xPixel = ((xTile - x) * tileSize).toInt().coerceIn(0, tileSize - 1)
    val yPixel = ((yTile - y) * tileSize).toInt().coerceIn(0, tileSize - 1)
    val pixel = bitmap.getPixel(xPixel, yPixel)
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    val elevation = -10000 + ((r * 256 * 256 + g * 256 + b) * 0.1)
    return elevation
} 