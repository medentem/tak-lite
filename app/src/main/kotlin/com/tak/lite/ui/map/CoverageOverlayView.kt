package com.tak.lite.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.tak.lite.model.CoverageGrid
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Projection
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Custom overlay view for rendering coverage analysis results
 */
class CoverageOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    private var projection: Projection? = null
    private var coverageGrid: CoverageGrid? = null
    private var currentZoom: Float = 0f
    private var showCoverageOverlay: Boolean = true
    
    // Paint for coverage rendering
    private val coveragePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        alpha = 30
    }
    
    // Paint for coverage boundaries
    private val boundaryPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.BLACK
        alpha = 64
    }
    
    // Paint for debug grid visualization
    private val debugGridPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = Color.RED
        alpha = 50
    }
    

    
    // Viewport optimization
    private var lastViewportUpdate: Long = 0
    private val VIEWPORT_UPDATE_THROTTLE_MS = 200L
    private var currentViewportBounds: RectF? = null
    
    companion object {
        private const val TAG = "CoverageOverlayView"
        private const val MIN_ZOOM_FOR_DETAILED_RENDERING = 8f
        private const val MAX_GRID_CELLS_FOR_DETAILED_RENDERING = 200 // Increased for better detail
        private const val DEBUG_GRID_VISUALIZATION = false // Set to true to see grid alignment
    }
    
    /**
     * Sets the map projection for coordinate conversion
     */
    fun setProjection(projection: Projection?) {
        this.projection = projection
        currentViewportBounds = null // Force viewport update
        invalidate()
    }
    
    /**
     * Updates the coverage grid data
     */
    fun updateCoverage(coverageGrid: CoverageGrid?) {
        android.util.Log.d("CoverageOverlayView", "updateCoverage called: grid=${coverageGrid != null}, size=${coverageGrid?.coverageData?.size}")
        this.coverageGrid = coverageGrid
        invalidate()
    }
    
    /**
     * Clears the coverage overlay
     */
    fun clearCoverage() {
        this.coverageGrid = null
        // Force immediate redraw to clear the overlay
        invalidate()
        android.util.Log.d("CoverageOverlayView", "Coverage overlay cleared")
    }
    
    /**
     * Sets whether to show the coverage overlay
     */
    fun setShowCoverageOverlay(show: Boolean) {
        this.showCoverageOverlay = show
        invalidate()
    }
    
    /**
     * Sets the current zoom level
     */
    fun setZoom(zoom: Float) {
        this.currentZoom = zoom
        currentViewportBounds = null // Force viewport update
        invalidate()
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (projection == null || !showCoverageOverlay || coverageGrid == null) {
            // Ensure we don't draw anything when coverage is cleared
            android.util.Log.d("CoverageOverlayView", "onDraw: Skipping draw - projection=${projection != null}, showOverlay=$showCoverageOverlay, grid=${coverageGrid != null}")
            return
        }
        
        // Debug logging for draw cycle
        android.util.Log.d("CoverageOverlayView", "Drawing coverage overlay: " +
            "currentZoom=$currentZoom, gridSize=${coverageGrid?.coverageData?.size}")
        
        try {
            drawCoverageGrid(canvas)
            
            // Debug grid visualization
            if (DEBUG_GRID_VISUALIZATION) {
                drawDebugGrid(canvas)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error drawing coverage overlay: ${e.message}", e)
        }
    }
    
    /**
     * Draws the coverage grid
     */
    private fun drawCoverageGrid(canvas: Canvas) {
        val grid = coverageGrid ?: return
        val viewportBounds = getVisibleMapBounds() ?: return
        
        // Determine rendering detail based on zoom level and grid size
        val useDetailedRendering = currentZoom >= MIN_ZOOM_FOR_DETAILED_RENDERING &&
                grid.coverageData.size <= MAX_GRID_CELLS_FOR_DETAILED_RENDERING
        
        if (useDetailedRendering) {
            drawDetailedCoverageGrid(canvas, grid, viewportBounds)
        } else {
            drawSimplifiedCoverageGrid(canvas, grid, viewportBounds)
        }
    }
    
    /**
     * Draws detailed coverage grid with perfect geographic alignment
     */
    private fun drawDetailedCoverageGrid(canvas: Canvas, grid: CoverageGrid, viewportBounds: RectF) {
        var totalPoints = 0
        var renderedPoints = 0
        var skippedPoints = 0
        var maxCoverage = 0f
        
        // Calculate cell size for perfect tiling
        val cellSize = calculateCellSize(grid.resolution)
        
        // Get grid bounds for coordinate mapping
        val gridBounds = grid.bounds
        val gridRows = grid.coverageData.size
        val gridCols = grid.coverageData[0].size
        
        // Calculate geographic spacing between grid points
        val latSpacing = gridBounds.latitudeSpan / (gridRows - 1)
        val lonSpacing = gridBounds.longitudeSpan / (gridCols - 1)
        
        // Pre-calculate all screen positions for better performance
        val screenPositions = Array(gridRows) { row ->
            Array(gridCols) { col ->
                val lat = gridBounds.southWest.latitude + (latSpacing * row)
                val lon = gridBounds.southWest.longitude + (lonSpacing * col)
                projection?.toScreenLocation(LatLng(lat, lon))
            }
        }
        
        for (row in grid.coverageData.indices) {
            for (col in grid.coverageData[row].indices) {
                val point = grid.coverageData[row][col]
                totalPoints++
                
                if (point.coverageProbability > maxCoverage) {
                    maxCoverage = point.coverageProbability
                }
                
                // Skip areas with no coverage (transparent), but show unknown areas (gray)
                if (point.coverageProbability < 0.05f && point.coverageProbability != -1f) {
                    skippedPoints++
                    continue
                }
                
                // Get pre-calculated screen position
                val screenPoint = screenPositions[row][col] ?: continue
                
                // Set coverage color
                coveragePaint.color = getCoverageColor(point.coverageProbability)
                coveragePaint.alpha = 120
                
                // Draw coverage cell with perfect alignment
                // Use the calculated cell size to ensure no gaps or overlaps
                val rect = RectF(
                    screenPoint.x - cellSize / 2,
                    screenPoint.y - cellSize / 2,
                    screenPoint.x + cellSize / 2,
                    screenPoint.y + cellSize / 2
                )
                
                // Only draw if the rectangle is at least partially visible
                if (rect.right >= 0 && rect.left <= width && rect.bottom >= 0 && rect.top <= height) {
                    canvas.drawRect(rect, coveragePaint)
                    renderedPoints++
                }
            }
        }
    }
    
    /**
     * Draws debug grid visualization to validate tile alignment
     */
    private fun drawDebugGrid(canvas: Canvas) {
        val grid = coverageGrid ?: return
        val cellSize = calculateCellSize(grid.resolution)
        
        // Get grid bounds for coordinate mapping
        val gridBounds = grid.bounds
        val gridRows = grid.coverageData.size
        val gridCols = grid.coverageData[0].size
        
        // Calculate geographic spacing between grid points
        val latSpacing = gridBounds.latitudeSpan / (gridRows - 1)
        val lonSpacing = gridBounds.longitudeSpan / (gridCols - 1)
        
        // Draw grid lines to visualize alignment
        for (row in 0 until gridRows) {
            for (col in 0 until gridCols) {
                val lat = gridBounds.southWest.latitude + (latSpacing * row)
                val lon = gridBounds.southWest.longitude + (lonSpacing * col)
                
                val screenPoint = projection?.toScreenLocation(LatLng(lat, lon))
                if (screenPoint == null) continue
                
                // Draw grid cell outline
                val rect = RectF(
                    screenPoint.x - cellSize / 2,
                    screenPoint.y - cellSize / 2,
                    screenPoint.x + cellSize / 2,
                    screenPoint.y + cellSize / 2
                )
                
                // Only draw if the rectangle is at least partially visible
                if (rect.right >= 0 && rect.left <= width && rect.bottom >= 0 && rect.top <= height) {
                    canvas.drawRect(rect, debugGridPaint)
                }
            }
        }
        
        // Draw grid center point for reference
        val centerLat = gridBounds.center.latitude
        val centerLon = gridBounds.center.longitude
        val centerScreenPoint = projection?.toScreenLocation(LatLng(centerLat, centerLon))
        
        if (centerScreenPoint != null) {
            val centerRect = RectF(
                centerScreenPoint.x - 5f,
                centerScreenPoint.y - 5f,
                centerScreenPoint.x + 5f,
                centerScreenPoint.y + 5f
            )
            canvas.drawRect(centerRect, debugGridPaint)
        }
        
        // Debug logging for grid validation
        android.util.Log.d("CoverageOverlayView", "Debug grid visualization: " +
            "gridSize=${gridRows}x${gridCols}, cellSize=$cellSize, " +
            "latSpacing=${latSpacing}°, lonSpacing=${lonSpacing}°")
    }
    
    /**
     * Draws simplified coverage grid with perfect geographic alignment
     */
    private fun drawSimplifiedCoverageGrid(canvas: Canvas, grid: CoverageGrid, viewportBounds: RectF) {
        // Create a simplified heat map representation
        val coveragePoints = mutableListOf<Triple<Int, Int, Float>>()
        
        for (row in grid.coverageData.indices) {
            for (col in grid.coverageData[row].indices) {
                val point = grid.coverageData[row][col]
                
                // Skip areas with no coverage (transparent)
                if (point.coverageProbability < 0.05f) {
                    continue
                }
                
                coveragePoints.add(Triple(row, col, point.coverageProbability))
            }
        }
        
        // Calculate cell size for perfect tiling using geographic resolution
        val cellSize = calculateCellSize(grid.resolution)
        
        // Get grid bounds for coordinate mapping
        val gridBounds = grid.bounds
        val gridRows = grid.coverageData.size
        val gridCols = grid.coverageData[0].size
        
        // Calculate geographic spacing between grid points
        val latSpacing = gridBounds.latitudeSpan / (gridRows - 1)
        val lonSpacing = gridBounds.longitudeSpan / (gridCols - 1)
        
        // Pre-calculate screen positions for better performance
        val screenPositions = Array(gridRows) { row ->
            Array(gridCols) { col ->
                val lat = gridBounds.southWest.latitude + (latSpacing * row)
                val lon = gridBounds.southWest.longitude + (lonSpacing * col)
                projection?.toScreenLocation(LatLng(lat, lon))
            }
        }
        
        // Draw coverage as perfectly tiled rectangles
        for ((row, col, coverage) in coveragePoints) {
            // Get pre-calculated screen position
            val screenPoint = screenPositions[row][col] ?: continue
            
            // Set coverage color
            coveragePaint.color = getCoverageColor(coverage)
            coveragePaint.alpha = 120
                
            val rect = RectF(
                screenPoint.x - cellSize / 2,
                screenPoint.y - cellSize / 2,
                screenPoint.x + cellSize / 2,
                screenPoint.y + cellSize / 2
            )
            
            // Only draw if the rectangle is at least partially visible
            if (rect.right >= 0 && rect.left <= width && rect.bottom >= 0 && rect.top <= height) {
                canvas.drawRect(rect, coveragePaint)
            }
        }
        
        // Debug logging for simplified rendering
        android.util.Log.d("CoverageOverlayView", "Simplified rendering results: " +
            "coveragePoints=${coveragePoints.size}, cellSize=$cellSize")
    }

    /**
     * Gets color for coverage probability
     * Shows coverage levels and unknown areas (gray)
     */
    private fun getCoverageColor(coverageProbability: Float): Int {
        return when {
            coverageProbability == -1f -> 0xFF9E9E9E.toInt() // Gray - Unknown coverage (no terrain data)
            coverageProbability >= 0.8f -> 0xFF4CAF50.toInt() // Green
            coverageProbability >= 0.6f -> 0xFFFFEB3B.toInt() // Yellow
            coverageProbability >= 0.4f -> 0xFFFF9800.toInt() // Orange
            coverageProbability >= 0.2f -> 0xFFF44336.toInt() // Red
            else -> 0x00000000.toInt() // Transparent - No coverage shown
        }
    }
    
    /**
     * Calculates cell size based on geographic resolution for perfect tiling
     * This ensures tiles exactly match the coverage grid spacing with no gaps or overlaps
     */
    private fun calculateCellSize(resolution: Double): Float {
        if (projection == null || currentZoom <= 0f) {
            return 100f // Fallback size
        }
        
        // Get the geographic bounds of the coverage grid
        val gridBounds = coverageGrid?.bounds ?: return 100f
        
        // Calculate the geographic spacing between grid points
        val gridRows = coverageGrid?.coverageData?.size ?: 1
        val gridCols = coverageGrid?.coverageData?.getOrNull(0)?.size ?: 1
        
        if (gridRows <= 1 || gridCols <= 1) {
            return 100f // Fallback for invalid grid
        }
        
        // Calculate geographic spacing in degrees
        val latSpacing = gridBounds.latitudeSpan / (gridRows - 1)
        val lonSpacing = gridBounds.longitudeSpan / (gridCols - 1)
        
        // Method 1: Calculate cell size by directly measuring the distance between adjacent grid points
        // This is the most accurate method as it uses the actual grid spacing
        val centerLat = gridBounds.center.latitude
        val centerLon = gridBounds.center.longitude
        
        // Get screen positions of adjacent grid points
        val centerPoint = projection?.toScreenLocation(LatLng(centerLat, centerLon))
        val adjacentLatPoint = projection?.toScreenLocation(LatLng(centerLat + latSpacing, centerLon))
        val adjacentLonPoint = projection?.toScreenLocation(LatLng(centerLat, centerLon + lonSpacing))
        
        if (centerPoint != null && adjacentLatPoint != null && adjacentLonPoint != null) {
            // Calculate pixel distances between adjacent grid points
            val latPixelDistance = abs(adjacentLatPoint.y - centerPoint.y)
            val lonPixelDistance = abs(adjacentLonPoint.x - centerPoint.x)
            
            // Use the larger distance to ensure tiles are big enough to eliminate gaps
            // The latitude and longitude spacing might be different due to map projection
            val pixelDistance = max(latPixelDistance, lonPixelDistance)
            
            // This pixel distance should be our cell size for perfect tiling
            val calculatedCellSize = pixelDistance.coerceIn(5f, 5000f) // Increased upper bound for high zoom levels
            
            // Debug logging
            android.util.Log.d("CoverageOverlayView", "Direct grid measurement calculation: " +
                "latSpacing=${latSpacing}°, lonSpacing=${lonSpacing}°, " +
                "latPixelDistance=$latPixelDistance, lonPixelDistance=$lonPixelDistance, " +
                "pixelDistance=$pixelDistance, calculatedCellSize=$calculatedCellSize")
            
            return calculatedCellSize
        }
        
        // Fallback: Use resolution-based calculation
        val centerLatForFallback = gridBounds.center.latitude
        
        // Get screen dimensions
        val screenWidth = width.toFloat()
        val screenHeight = height.toFloat()
        
        if (screenWidth <= 0f || screenHeight <= 0f) {
            return 100f
        }
        
        // Calculate meters per pixel based on the COVERAGE GRID bounds, not screen bounds
        val gridLatSpanMeters = gridBounds.latitudeSpan * 111320.0 // meters per degree latitude
        val gridLonSpanMeters = gridBounds.longitudeSpan * 111320.0 * 
            cos(Math.toRadians(centerLatForFallback)) // meters per degree longitude at latitude
        
        // Convert grid bounds to screen coordinates to get the actual pixel dimensions
        val gridTopLeft = projection?.toScreenLocation(gridBounds.southWest)
        val gridBottomRight = projection?.toScreenLocation(gridBounds.northEast)
        
        if (gridTopLeft == null || gridBottomRight == null) {
            return 100f
        }
        
        // Calculate the pixel dimensions of the coverage grid
        val gridPixelWidth = abs(gridBottomRight.x - gridTopLeft.x)
        val gridPixelHeight = abs(gridBottomRight.y - gridTopLeft.y)
        
        // Calculate meters per pixel based on the coverage grid's actual pixel dimensions
        val metersPerPixelX = gridLonSpanMeters / gridPixelWidth
        val metersPerPixelY = gridLatSpanMeters / gridPixelHeight
        val metersPerPixel = min(metersPerPixelX, metersPerPixelY)
        
        // Calculate cell size in pixels based on the actual resolution
        val cellSizeInPixels = (resolution / metersPerPixel).toFloat()
        
        // Apply a small adjustment factor to ensure tiles are edge-to-edge
        val adjustedCellSize = cellSizeInPixels * 1.1f
        
        // Apply reasonable bounds
        val calculatedCellSize = adjustedCellSize.coerceIn(5f, 5000f) // Increased upper bound for high zoom levels
        
        // Debug logging
        android.util.Log.d("CoverageOverlayView", "Fallback resolution-based calculation: " +
            "resolution=${resolution}m, metersPerPixel=$metersPerPixel, " +
            "cellSizeInPixels=$cellSizeInPixels, adjustedCellSize=$adjustedCellSize, " +
            "calculatedCellSize=$calculatedCellSize")
        
        return calculatedCellSize
    }
    

    
    /**
     * Gets visible map bounds for viewport culling
     */
    private fun getVisibleMapBounds(): RectF? {
        val now = System.currentTimeMillis()
        
        // Throttle viewport updates
        if (now - lastViewportUpdate < VIEWPORT_UPDATE_THROTTLE_MS && currentViewportBounds != null) {
            return currentViewportBounds
        }
        
        if (projection == null || width == 0 || height == 0) {
            return null
        }
        
        // Get screen corners and convert to lat/lng
        val topLeft = projection?.fromScreenLocation(PointF(0f, 0f))
        val bottomRight = projection?.fromScreenLocation(PointF(width.toFloat(), height.toFloat()))
        
        if (topLeft == null || bottomRight == null) {
            return null
        }
        
        // Add margin for coverage partially off-screen
        val margin = 0.1f
        val bounds = RectF(
            (topLeft.longitude - margin).toFloat(),
            (topLeft.latitude + margin).toFloat(),
            (bottomRight.longitude + margin).toFloat(),
            (bottomRight.latitude - margin).toFloat()
        )
        
        currentViewportBounds = bounds
        lastViewportUpdate = now
        return bounds
    }
}