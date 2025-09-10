package com.tak.lite.notification

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.CoordinateUtils.toRadians
import com.tak.lite.util.haversine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
@Singleton
class MapImageGenerator @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "MapImageGenerator"
        private const val THUMBNAIL_SIZE = 256
        private const val CACHE_DIR = "map_thumbnails"
        private const val MAX_CACHE_SIZE = 50 // Maximum number of cached thumbnails
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR)
    
    init {
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
    }

    suspend fun generateMapThumbnail(
        annotation: MapAnnotation,
        userLatitude: Double? = null,
        userLongitude: Double? = null
    ): Bitmap? = withContext(Dispatchers.Default) {
        try {
            // Check cache first
            val cacheKey = generateCacheKey(annotation, userLatitude, userLongitude)
            val cachedBitmap = loadFromCache(cacheKey)
            if (cachedBitmap != null) {
                Log.d(TAG, "Using cached thumbnail for annotation ${annotation.id}")
                return@withContext cachedBitmap
            }

            // Generate new thumbnail
            val thumbnail = generateThumbnail(annotation, userLatitude, userLongitude)
            
            // Cache the result
            if (thumbnail != null) {
                saveToCache(cacheKey, thumbnail)
                cleanupCache()
            }
            
            thumbnail
        } catch (e: Exception) {
            Log.e(TAG, "Error generating map thumbnail for annotation ${annotation.id}", e)
            null
        }
    }

    private suspend fun generateThumbnail(
        annotation: MapAnnotation,
        userLatitude: Double?,
        userLongitude: Double?
    ): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            
            // Get annotation position for map center
            val annotationPosition = getAnnotationPosition(annotation)
            if (annotationPosition != null) {
                // Download and draw actual map tiles
                val success = drawMapTiles(canvas, annotationPosition.lt, annotationPosition.lng)
                if (!success) {
                    // Fallback to simple background if tile download fails
                    drawFallbackBackground(canvas)
                }
            } else {
                // Fallback to simple background if no position
                drawFallbackBackground(canvas)
            }
            
            // Draw annotation
            drawAnnotation(canvas, annotation)
            
            // Draw user location if available
            if (userLatitude != null && userLongitude != null) {
                drawUserLocation(canvas, userLatitude, userLongitude, annotation)
            }
            
            // Draw distance and direction info
            drawDistanceInfo(canvas, annotation, userLatitude, userLongitude)
            
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Error creating thumbnail bitmap", e)
            return null
        }
    }

    private fun getAnnotationPosition(annotation: MapAnnotation): LatLngSerializable? {
        return when (annotation) {
            is MapAnnotation.PointOfInterest -> annotation.position
            is MapAnnotation.Line -> annotation.points.firstOrNull()
            is MapAnnotation.Area -> annotation.center
            is MapAnnotation.Polygon -> {
                if (annotation.points.isNotEmpty()) {
                    val avgLat = annotation.points.map { it.lt }.average()
                    val avgLng = annotation.points.map { it.lng }.average()
                    LatLngSerializable(avgLat, avgLng)
                } else null
            }
            is MapAnnotation.Deletion -> null
        }
    }

    private suspend fun drawMapTiles(canvas: Canvas, centerLat: Double, centerLng: Double): Boolean {
        try {
            // Use zoom level 15 for good detail in thumbnail
            val zoom = 15
            val tileSize = 256
            
            // Calculate tile coordinates for center point
            val n = 2.0.pow(zoom)
            val tileX = ((centerLng + 180.0) / 360.0 * n).toInt()
            val tileY = ((1.0 - kotlin.math.ln(kotlin.math.tan(toRadians(centerLat)) + 1.0 / kotlin.math.cos(toRadians(centerLat))) / kotlin.math.PI) / 2.0 * n).toInt()
            
            // Calculate the exact position within the tile
            val tileXFloat = (centerLng + 180.0) / 360.0 * n
            val tileYFloat = (1.0 - kotlin.math.ln(kotlin.math.tan(toRadians(centerLat)) + 1.0 / kotlin.math.cos(toRadians(centerLat))) / kotlin.math.PI) / 2.0 * n
            
            // Calculate offset within the tile (0.0 to 1.0)
            val offsetX = tileXFloat - tileX
            val offsetY = tileYFloat - tileY
            
            // Download a 2x2 grid of tiles to ensure we have complete coverage
            val tiles = mutableListOf<Pair<Bitmap?, Pair<Int, Int>>>()
            
            // Download tiles in a 2x2 grid around the center tile
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val tile = downloadTile(zoom, tileX + dx, tileY + dy)
                    tiles.add(Pair(tile, Pair(tileX + dx, tileY + dy)))
                }
            }
            
            // Draw all tiles at their calculated positions
            var hasAnyTile = false
            for ((tileBitmap, tileCoords) in tiles) {
                if (tileBitmap != null) {
                    val (tileXCoord, tileYCoord) = tileCoords
                    val tileOffsetX = tileXCoord - tileX
                    val tileOffsetY = tileYCoord - tileY
                    
                    // Calculate position for this tile
                    val drawX = (THUMBNAIL_SIZE / 2) - (offsetX * THUMBNAIL_SIZE) + (tileOffsetX * THUMBNAIL_SIZE)
                    val drawY = (THUMBNAIL_SIZE / 2) - (offsetY * THUMBNAIL_SIZE) + (tileOffsetY * THUMBNAIL_SIZE)
                    
                    // Only draw if the tile is within the thumbnail bounds
                    if (drawX > -THUMBNAIL_SIZE && drawX < THUMBNAIL_SIZE && 
                        drawY > -THUMBNAIL_SIZE && drawY < THUMBNAIL_SIZE) {
                        canvas.drawBitmap(tileBitmap, drawX.toFloat(), drawY.toFloat(), null)
                        hasAnyTile = true
                    }
                    tileBitmap.recycle()
                }
            }
            
            return hasAnyTile
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading map tiles", e)
            return false
        }
    }

    private suspend fun downloadTile(zoom: Int, x: Int, y: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val url = "https://tile.openstreetmap.org/$zoom/$x/$y.png"
            Log.d(TAG, "Downloading tile: $url")
            
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "tak-lite/1.0 (https://github.com/developer)")
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            
            if (connection.responseCode == 200) {
                val inputStream = connection.inputStream
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                connection.disconnect()
                return@withContext bitmap
            } else {
                Log.w(TAG, "Failed to download tile: HTTP ${connection.responseCode}")
                connection.disconnect()
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading tile $zoom/$x/$y", e)
            return@withContext null
        }
    }

    private fun drawFallbackBackground(canvas: Canvas) {
        // Draw a simple fallback background
        val backgroundPaint = Paint().apply {
            color = Color.parseColor("#E8F4FD")
            style = Paint.Style.FILL
        }
        canvas.drawRect(0f, 0f, THUMBNAIL_SIZE.toFloat(), THUMBNAIL_SIZE.toFloat(), backgroundPaint)
        
        // Draw subtle grid lines
        val gridPaint = Paint().apply {
            color = Color.parseColor("#D0E7F7")
            strokeWidth = 0.5f
            style = Paint.Style.STROKE
        }
        
        val gridSize = THUMBNAIL_SIZE / 8
        for (i in 0..8) {
            val pos = i * gridSize
            canvas.drawLine(pos.toFloat(), 0f, pos.toFloat(), THUMBNAIL_SIZE.toFloat(), gridPaint)
            canvas.drawLine(0f, pos.toFloat(), THUMBNAIL_SIZE.toFloat(), pos.toFloat(), gridPaint)
        }
    }

    private fun drawAnnotation(canvas: Canvas, annotation: MapAnnotation) {
        val centerX = THUMBNAIL_SIZE / 2f
        val centerY = THUMBNAIL_SIZE / 2f
        
        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
        }
        
        when (annotation) {
            is MapAnnotation.PointOfInterest -> {
                drawPointOfInterest(canvas, centerX, centerY, annotation, paint)
            }
            is MapAnnotation.Line -> {
                drawLine(canvas, centerX, centerY, annotation, paint)
            }
            is MapAnnotation.Area -> {
                drawArea(canvas, centerX, centerY, annotation, paint)
            }
            is MapAnnotation.Polygon -> {
                drawPolygon(canvas, centerX, centerY, annotation, paint)
            }
            is MapAnnotation.Deletion -> {
                // Don't draw deletions
            }
        }
    }

    private fun drawPointOfInterest(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        poi: MapAnnotation.PointOfInterest,
        paint: Paint
    ) {
        paint.color = getAnnotationColor(poi.color)
        
        when (poi.shape) {
            com.tak.lite.model.PointShape.CIRCLE -> {
                canvas.drawCircle(centerX, centerY, 12f, paint)
            }
            com.tak.lite.model.PointShape.SQUARE -> {
                canvas.drawRect(centerX - 12f, centerY - 12f, centerX + 12f, centerY + 12f, paint)
            }
            com.tak.lite.model.PointShape.TRIANGLE -> {
                val path = android.graphics.Path()
                path.moveTo(centerX, centerY - 12f)
                path.lineTo(centerX - 12f, centerY + 12f)
                path.lineTo(centerX + 12f, centerY + 12f)
                path.close()
                canvas.drawPath(path, paint)
            }
            com.tak.lite.model.PointShape.EXCLAMATION -> {
                // Draw exclamation mark
                paint.strokeWidth = 4f
                paint.style = Paint.Style.STROKE
                canvas.drawLine(centerX, centerY - 8f, centerX, centerY + 8f, paint)
                canvas.drawCircle(centerX, centerY + 4f, 2f, paint)
            }
        }
    }

    private fun drawLine(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        line: MapAnnotation.Line,
        paint: Paint
    ) {
        paint.color = getAnnotationColor(line.color)
        paint.strokeWidth = 6f
        paint.style = Paint.Style.STROKE
        
        // Draw a simplified line representation
        canvas.drawLine(centerX - 30f, centerY, centerX + 30f, centerY, paint)
        
        // Draw arrow if enabled
        if (line.arrowHead) {
            val arrowPaint = Paint().apply {
                color = getAnnotationColor(line.color)
                style = Paint.Style.FILL
            }
            val path = android.graphics.Path()
            path.moveTo(centerX + 30f, centerY)
            path.lineTo(centerX + 20f, centerY - 5f)
            path.lineTo(centerX + 20f, centerY + 5f)
            path.close()
            canvas.drawPath(path, arrowPaint)
        }
    }

    private fun drawArea(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        area: MapAnnotation.Area,
        paint: Paint
    ) {
        paint.color = getAnnotationColor(area.color)
        paint.alpha = 100 // Semi-transparent
        
        // Draw circle representing the area
        val radius = 25f // Fixed radius for thumbnail
        canvas.drawCircle(centerX, centerY, radius, paint)
        
        // Draw border
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawCircle(centerX, centerY, radius, paint)
    }

    private fun drawPolygon(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        polygon: MapAnnotation.Polygon,
        paint: Paint
    ) {
        paint.color = getAnnotationColor(polygon.color)
        paint.alpha = (polygon.fillOpacity * 255).toInt()
        
        // Draw a simplified polygon (hexagon for thumbnail)
        val path = android.graphics.Path()
        val radius = 20f
        for (i in 0..5) {
            val angle = Math.PI / 3 * i
            val x = centerX + radius * Math.cos(angle).toFloat()
            val y = centerY + radius * Math.sin(angle).toFloat()
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        path.close()
        canvas.drawPath(path, paint)
        
        // Draw border
        paint.alpha = 255
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = polygon.strokeWidth
        canvas.drawPath(path, paint)
    }

    private fun drawUserLocation(
        canvas: Canvas,
        userLat: Double,
        userLng: Double,
        annotation: MapAnnotation
    ) {
        // Calculate relative position of user to annotation
        val annotationLatLng = when (annotation) {
            is MapAnnotation.PointOfInterest -> annotation.position
            is MapAnnotation.Line -> annotation.points.firstOrNull()
            is MapAnnotation.Area -> annotation.center
            is MapAnnotation.Polygon -> {
                if (annotation.points.isNotEmpty()) {
                    val avgLat = annotation.points.map { it.lt }.average()
                    val avgLng = annotation.points.map { it.lng }.average()
                    LatLngSerializable(avgLat, avgLng)
                } else null
            }
            is MapAnnotation.Deletion -> null
        } ?: return

        val distance = haversine(userLat, userLng, annotationLatLng.lt, annotationLatLng.lng)
        val maxDistance = 1000.0 // 1km max distance for thumbnail
        
        if (distance > maxDistance) {
            // User is too far, don't draw
            return
        }
        
        // Calculate position on thumbnail (simplified projection)
        val centerX = THUMBNAIL_SIZE / 2f
        val centerY = THUMBNAIL_SIZE / 2f
        
        val deltaLat = userLat - annotationLatLng.lt
        val deltaLng = userLng - annotationLatLng.lng
        
        // Simple projection (not accurate but good enough for thumbnail)
        val scale = (THUMBNAIL_SIZE / 2f) / (maxDistance / 111000.0) // Rough conversion to degrees
        val userX = centerX + (deltaLng * scale).toFloat()
        val userY = centerY - (deltaLat * scale).toFloat()
        
        // Draw user location
        val paint = Paint().apply {
            color = Color.BLUE
            style = Paint.Style.FILL
            isAntiAlias = true
        }
        canvas.drawCircle(userX, userY, 8f, paint)
        
        // Draw border
        paint.color = Color.WHITE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawCircle(userX, userY, 8f, paint)
    }

    private fun drawDistanceInfo(
        canvas: Canvas,
        annotation: MapAnnotation,
        userLat: Double?,
        userLng: Double?
    ) {
        if (userLat == null || userLng == null) return
        
        val distance = calculateDistance(annotation, userLat, userLng) ?: return
        
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 12f
            isAntiAlias = true
        }
        
        val distanceText = when {
            distance < 1000 -> "${distance.toInt()}m"
            else -> "${String.format("%.1f", distance / 1000)}km"
        }
        
        val textBounds = Rect()
        paint.getTextBounds(distanceText, 0, distanceText.length, textBounds)
        
        val x = (THUMBNAIL_SIZE - textBounds.width()) / 2f
        val y = THUMBNAIL_SIZE - 10f
        
        // Draw background
        val bgPaint = Paint().apply {
            color = Color.WHITE
            alpha = 200
        }
        canvas.drawRect(
            x - 4f, y - textBounds.height() - 4f,
            x + textBounds.width() + 4f, y + 4f,
            bgPaint
        )
        
        canvas.drawText(distanceText, x, y, paint)
    }

    private fun calculateDistance(annotation: MapAnnotation, userLat: Double, userLng: Double): Double? {
        val annotationLatLng = when (annotation) {
            is MapAnnotation.PointOfInterest -> annotation.position
            is MapAnnotation.Line -> annotation.points.firstOrNull()
            is MapAnnotation.Area -> annotation.center
            is MapAnnotation.Polygon -> {
                if (annotation.points.isNotEmpty()) {
                    val avgLat = annotation.points.map { it.lt }.average()
                    val avgLng = annotation.points.map { it.lng }.average()
                    LatLngSerializable(avgLat, avgLng)
                } else null
            }
            is MapAnnotation.Deletion -> null
        } ?: return null

        return haversine(userLat, userLng, annotationLatLng.lt, annotationLatLng.lng)
    }

    private fun getAnnotationColor(annotationColor: com.tak.lite.model.AnnotationColor): Int {
        return when (annotationColor) {
            com.tak.lite.model.AnnotationColor.RED -> Color.RED
            com.tak.lite.model.AnnotationColor.YELLOW -> Color.YELLOW
            com.tak.lite.model.AnnotationColor.GREEN -> Color.GREEN
            com.tak.lite.model.AnnotationColor.BLACK -> Color.BLACK
            com.tak.lite.model.AnnotationColor.WHITE -> Color.WHITE
        }
    }

    private fun generateCacheKey(
        annotation: MapAnnotation,
        userLatitude: Double?,
        userLongitude: Double?
    ): String {
        return "${annotation.id}_${annotation.timestamp}_${userLatitude}_${userLongitude}"
    }

    private fun loadFromCache(cacheKey: String): Bitmap? {
        return try {
            val file = File(cacheDir, "$cacheKey.png")
            if (file.exists() && file.length() > 0) {
                BitmapFactory.decodeFile(file.absolutePath)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading from cache", e)
            null
        }
    }

    private fun saveToCache(cacheKey: String, bitmap: Bitmap) {
        try {
            val file = File(cacheDir, "$cacheKey.png")
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error saving to cache", e)
        }
    }

    private fun cleanupCache() {
        try {
            val files = cacheDir.listFiles() ?: return
            if (files.size > MAX_CACHE_SIZE) {
                // Sort by modification time and delete oldest
                files.sortedBy { it.lastModified() }
                    .take(files.size - MAX_CACHE_SIZE)
                    .forEach { it.delete() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up cache", e)
        }
    }
}
