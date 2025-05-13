package com.tak.lite.util

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

suspend fun saveTilePngWithType(context: Context, type: String, zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "tiles/$type/$zoom/$x")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$y.png")
        FileOutputStream(file).use { it.write(bytes) }
        true
    } catch (e: Exception) {
        android.util.Log.e("OfflineTiles", "Failed to save $type tile $zoom/$x/$y: ${e.message}")
        false
    }
}

suspend fun saveTilePbfWithType(context: Context, type: String, zoom: Int, x: Int, y: Int, bytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
    try {
        val dir = File(context.filesDir, "tiles/$type/$zoom/$x")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "$y.pbf")
        FileOutputStream(file).use { it.write(bytes) }
        true
    } catch (e: Exception) {
        android.util.Log.e("OfflineTiles", "Failed to save $type tile $zoom/$x/$y: ${e.message}")
        false
    }
} 