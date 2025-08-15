package com.tak.lite.ui.map

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tak.lite.R
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.UnitManager
import com.tak.lite.util.getOfflineElevation
import com.tak.lite.util.haversine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class ElevationChartBottomSheet(
    private val line: MapAnnotation.Line
) : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        android.util.Log.d("ElevationChartBottomSheet", "onCreateView called")
        val context = requireContext()
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(32, 32, 32, 32)

        // Detect dark mode
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDarkMode) Color.parseColor("#181A20") else Color.WHITE
        val lineColor = if (isDarkMode) ContextCompat.getColor(context, R.color.primary_dark) else ContextCompat.getColor(context, R.color.primary_light)
        val textColor = if (isDarkMode) Color.parseColor("#B0B3B8") else Color.DKGRAY

        layout.setBackgroundColor(bgColor)

        // Add title
        val titleText = TextView(context)
        titleText.text = "Elevation Profile"
        titleText.textSize = 18f
        titleText.setTextColor(textColor)
        titleText.typeface = android.graphics.Typeface.DEFAULT_BOLD
        titleText.setPadding(0, 0, 0, 16)
        layout.addView(titleText)

        // Add progress indicator
        val progressLayout = LinearLayout(context)
        progressLayout.orientation = LinearLayout.VERTICAL
        progressLayout.visibility = View.VISIBLE
        progressLayout.setPadding(0, 16, 0, 16)

        val progressText = TextView(context)
        progressText.text = "Loading elevation data..."
        progressText.textSize = 14f
        progressText.setTextColor(textColor)
        progressText.setPadding(0, 0, 0, 8)
        progressLayout.addView(progressText)

        val progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        progressBar.isIndeterminate = true
        progressBar.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        progressLayout.addView(progressBar)

        layout.addView(progressLayout)

        val chart = LineChart(context)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            500
        )
        chart.visibility = View.GONE

        chart.setBackgroundColor(bgColor)
        chart.setNoDataText("No elevation data")
        chart.setNoDataTextColor(textColor)
        chart.setDrawGridBackground(false)
        chart.setDrawBorders(true)
        chart.description.isEnabled = false
        chart.legend.isEnabled = false
        chart.setTouchEnabled(true)
        chart.setViewPortOffsets(120f, 20f, 20f, 60f)

        // X Axis
        val xAxis = chart.xAxis
        xAxis.isEnabled = true
        xAxis.setDrawAxisLine(true)
        xAxis.setDrawGridLines(false)
        xAxis.textColor = if (isDarkMode) Color.WHITE else textColor
        xAxis.textSize = 12f
        xAxis.position = com.github.mikephil.charting.components.XAxis.XAxisPosition.BOTTOM
        xAxis.setLabelCount(8, true)
        xAxis.setAvoidFirstLastClipping(false)
        xAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return if (value < 0.01f) "0 ${UnitManager.getDistanceUnitLabel(context)}"
                       else UnitManager.metersToDistance(value.toDouble(), context)
            }
        }
        // Y Axis
        val leftAxis = chart.axisLeft
        leftAxis.isEnabled = true
        leftAxis.setDrawAxisLine(true)
        leftAxis.setDrawGridLines(false)
        leftAxis.textColor = if (isDarkMode) Color.WHITE else textColor
        leftAxis.textSize = 12f
        leftAxis.setLabelCount(8, true)
        leftAxis.valueFormatter = object : com.github.mikephil.charting.formatter.ValueFormatter() {
            override fun getFormattedValue(value: Float): String {
                return UnitManager.metersToElevation(value.toDouble(), context)
            }
        }
        val rightAxis = chart.axisRight
        rightAxis.isEnabled = false

        // Launch coroutine to fetch elevations and update chart
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
            val entries = mutableListOf<Entry>()
            val points = line.points
            val filesDir = context.filesDir
            val zoom = 13 // or another appropriate zoom level for DEM
            val mapController = (activity as? MapControllerProvider)?.getMapController()
            var minElevation = Float.MAX_VALUE
            var maxElevation = Float.MIN_VALUE

            // Calculate total line length for adaptive sampling
            var totalLineLength = 0.0
            for (i in 0 until points.size - 1) {
                val p1 = points[i].toMapLibreLatLng()
                val p2 = points[i + 1].toMapLibreLatLng()
                totalLineLength += haversine(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
            }

            // Adaptive sampling based on line length
            val samplingDistance = when {
                totalLineLength < 1000 -> 30.0  // Short lines: 30m intervals
                totalLineLength < 5000 -> 50.0  // Medium lines: 50m intervals  
                totalLineLength < 20000 -> 100.0 // Long lines: 100m intervals
                else -> 200.0 // Very long lines: 200m intervals
            }

            android.util.Log.d("ElevationChartBottomSheet", "Line length: ${totalLineLength}m, using ${samplingDistance}m sampling")

            // --- Interpolate more points for smoother chart with adaptive sampling ---
            fun interpolatePoints(p1: org.maplibre.android.geometry.LatLng, p2: org.maplibre.android.geometry.LatLng, steps: Int): List<org.maplibre.android.geometry.LatLng> {
                val result = mutableListOf<org.maplibre.android.geometry.LatLng>()
                for (i in 1 until steps) {
                    val t = i / steps.toFloat()
                    val lat = p1.latitude + (p2.latitude - p1.latitude) * t
                    val lon = p1.longitude + (p2.longitude - p1.longitude) * t
                    result.add(org.maplibre.android.geometry.LatLng(lat, lon))
                }
                return result
            }
            val densePoints = mutableListOf<org.maplibre.android.geometry.LatLng>()
            for (i in points.indices) {
                val p1 = points[i].toMapLibreLatLng()
                densePoints.add(p1)
                if (i < points.size - 1) {
                    val p2 = points[i + 1].toMapLibreLatLng()
                    val dist = haversine(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
                    val steps = (dist / samplingDistance).toInt().coerceAtLeast(1)
                    densePoints.addAll(interpolatePoints(p1, p2, steps))
                }
            }

            // Limit the number of points for very long lines to maintain performance
            val maxPoints = when {
                totalLineLength < 10000 -> densePoints.size // No limit for shorter lines
                totalLineLength < 50000 -> 500 // Limit to 500 points for medium lines
                else -> 300 // Limit to 300 points for very long lines
            }

            if (densePoints.size > maxPoints) {
                val step = densePoints.size / maxPoints
                val limitedPoints = mutableListOf<org.maplibre.android.geometry.LatLng>()
                for (i in densePoints.indices step step) {
                    limitedPoints.add(densePoints[i])
                }
                // Always include the last point
                if (limitedPoints.last() != densePoints.last()) {
                    limitedPoints.add(densePoints.last())
                }
                densePoints.clear()
                densePoints.addAll(limitedPoints)
                android.util.Log.d("ElevationChartBottomSheet", "Reduced points from ${densePoints.size} to ${limitedPoints.size} for performance")
            }

            // --- Sample elevations for all dense points with progress updates ---
            var cumulativeDistance = 0f
            var prevLat: Double? = null
            var prevLon: Double? = null
            var tilesDownloaded = 0
            val totalTilesNeeded: Int
            val tilesToDownload = mutableSetOf<Pair<Int, Int>>()

            // First pass: identify which tiles need to be downloaded
            for (pt in densePoints) {
                val lat = pt.latitude
                val lon = pt.longitude
                val elevation = getOfflineElevation(lat, lon, zoom, filesDir)
                if (elevation == null) {
                    val n = 2.0.pow(zoom.toDouble())
                    val x = ((lon + 180.0) / 360.0 * n).toInt()
                    val latRad = Math.toRadians(lat)
                    val y = ((1.0 - ln(tan(latRad) + 1 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
                    tilesToDownload.add(Pair(x, y))
                }
            }
            totalTilesNeeded = tilesToDownload.size

            android.util.Log.d("ElevationChartBottomSheet", "Need to download $totalTilesNeeded terrain tiles")

            if (totalTilesNeeded > 0) {
                progressText.text = "Downloading terrain data... (0/$totalTilesNeeded tiles)"
                progressBar.isIndeterminate = false
                progressBar.max = totalTilesNeeded
                progressBar.progress = 0

                // Download missing tiles
                var downloadSuccess = true
                for ((x, y) in tilesToDownload) {
                    if (mapController != null) {
                        val success = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            mapController.downloadTerrainDemTile(zoom, x, y)
                        }
                        tilesDownloaded++
                        progressText.text = "Downloading terrain data... ($tilesDownloaded/$totalTilesNeeded tiles)"
                        progressBar.progress = tilesDownloaded
                        
                        if (!success) {
                            downloadSuccess = false
                        }
                    } else {
                        downloadSuccess = false
                        break
                    }
                }

                if (!downloadSuccess) {
                    progressText.text = "Warning: Some terrain data unavailable. Using estimated elevations."
                    progressText.setTextColor(Color.parseColor("#FFA500")) // Orange warning color
                }
            }

            progressText.text = "Processing elevation data..."
            progressBar.isIndeterminate = true

            // Second pass: collect elevation data
            for ((idx, pt) in densePoints.withIndex()) {
                val lat = pt.latitude
                val lon = pt.longitude
                val elevation = getOfflineElevation(lat, lon, zoom, filesDir)
                val usedElevation = elevation ?: (100 + (idx * 2)) // fallback stub
                
                if (usedElevation.toFloat() < minElevation) minElevation = usedElevation.toFloat()
                if (usedElevation.toFloat() > maxElevation) maxElevation = usedElevation.toFloat()
                
                if (prevLat != null && prevLon != null) {
                    val dist = haversine(prevLat, prevLon, lat, lon).toFloat()
                    cumulativeDistance += dist
                }
                entries.add(Entry(cumulativeDistance, usedElevation.toFloat()))
                prevLat = lat
                prevLon = lon
            }

            // Check if we have any real elevation data
            val hasRealElevationData = entries.any { entry ->
                val elevation = entry.y
                // Check if elevation is not the fallback pattern (100 + idx * 2)
                val idx = entries.indexOf(entry)
                elevation != (100 + (idx * 2)).toFloat()
            }

            if (!hasRealElevationData) {
                progressText.text = "No terrain data available for this area. Showing estimated profile."
                progressText.setTextColor(Color.parseColor("#FF6B6B")) // Red warning color
            }

            android.util.Log.d("ElevationChartBottomSheet", "Processed ${entries.size} elevation points, real data: $hasRealElevationData")

            // Hide progress and show chart
            progressLayout.visibility = View.GONE
            chart.visibility = View.VISIBLE

            val dataSet = LineDataSet(entries, null)
            dataSet.color = lineColor
            dataSet.setDrawCircles(false)
            dataSet.lineWidth = 2.5f
            dataSet.setDrawValues(false)
            dataSet.setDrawHighlightIndicators(false)
            dataSet.isHighlightEnabled = true
            dataSet.setDrawFilled(true)
            dataSet.fillAlpha = 60
            dataSet.fillColor = lineColor
            val lineData = LineData(dataSet)
            chart.data = lineData

            // Add a custom MarkerView that draws a subtle dot at the highlighted entry
            val marker = object : com.github.mikephil.charting.components.MarkerView(context, android.R.layout.simple_list_item_1) {
                var distMi: Float? = null
                var elevFt: Float? = null
                override fun refreshContent(e: Entry?, highlight: com.github.mikephil.charting.highlight.Highlight?) {
                    if (e != null) {
                        distMi = e.x / 1609.344f
                        elevFt = e.y * 3.28084f
                    } else {
                        distMi = null
                        elevFt = null
                    }
                }
                override fun draw(canvas: android.graphics.Canvas, posX: Float, posY: Float) {
                    // Draw the dot
                    val radius = 10f
                    val paint = android.graphics.Paint().apply {
                        color = Color.WHITE
                        style = android.graphics.Paint.Style.FILL
                        isAntiAlias = true
                        setShadowLayer(6f, 0f, 0f, Color.argb(80, 0, 0, 0))
                    }
                    canvas.drawCircle(posX, posY, radius, paint)

                    // Draw the callout above the dot if values are available
                    distMi?.let { d ->
                        elevFt?.let { e ->
                            val text = "${UnitManager.metersToDistance(d.toDouble(), context)}\n${UnitManager.metersToElevation(e.toDouble(), context)}"
                            val textPaint = android.graphics.Paint().apply {
                                color = Color.WHITE
                                textSize = 36f
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                typeface = android.graphics.Typeface.DEFAULT_BOLD
                            }
                            val lines = text.split("\n")
                            val fm = textPaint.fontMetrics
                            val lineHeight = fm.descent - fm.ascent
                            val padding = 18f
                            val rectWidth = 160f
                            val rectHeight = lineHeight * lines.size + padding * 2
                            val rectLeft = posX - rectWidth / 2
                            val rectTop = posY - radius - rectHeight - 12f
                            val rectRight = posX + rectWidth / 2
                            val rectBottom = rectTop + rectHeight
                            val bgPaint = android.graphics.Paint().apply {
                                color = Color.argb(200, 30, 30, 30)
                                style = android.graphics.Paint.Style.FILL
                                isAntiAlias = true
                            }
                            // Draw rounded background
                            canvas.drawRoundRect(
                                rectLeft, rectTop, rectRight, rectBottom, 24f, 24f, bgPaint
                            )
                            // Draw text lines
                            var textY = rectTop + padding - fm.ascent
                            for (line in lines) {
                                canvas.drawText(line, posX, textY, textPaint)
                                textY += lineHeight
                            }
                        }
                    }
                }
                override fun getOffset(): com.github.mikephil.charting.utils.MPPointF {
                    // Center the dot
                    return com.github.mikephil.charting.utils.MPPointF(0f, 0f)
                }
            }
            chart.marker = marker

            // --- Dynamic axis scaling ---
            val xMin: Float = entries.minOfOrNull { it.x } ?: 0.0f
            val xMax: Float = entries.maxOfOrNull { it.x } ?: 1.0f
            val yMin: Float = entries.minOfOrNull { it.y } ?: 0.0f
            val yMax: Float = entries.maxOfOrNull { it.y } ?: 1.0f
            val xRange = (xMax - xMin).coerceAtLeast(0.1f)
            val yRange = (yMax - yMin).coerceAtLeast(0.1f)
            // X Axis: miles
            val xRangeMi = xRange / 1609.344f
            // --- Declutter X axis: max 4 labels, evenly spaced ---
            val maxXLabels = 4
            val xLabelCount = when {
                xRangeMi < 1f -> 2
                xRangeMi < 2f -> 3
                else -> maxXLabels
            }
            val xGranularityMi = if (xLabelCount > 1) xRangeMi / (xLabelCount - 1) else xRangeMi
            xAxis.setLabelCount(xLabelCount, true)
            xAxis.granularity = xGranularityMi * 1609.344f
            xAxis.axisMinimum = xMin
            xAxis.axisMaximum = xMax + xGranularityMi * 1609.344f * 0.05f // small padding
            // Y Axis: feet
            val yRangeFt = yRange * 3.28084f
            val yGranularityFt = when {
                yRangeFt < 20f -> 2f
                yRangeFt < 50f -> 5f
                yRangeFt < 200f -> 10f
                else -> 25f
            }
            val yLabelCount = when {
                yRangeFt < 20f -> 4
                yRangeFt < 50f -> 6
                yRangeFt < 200f -> 8
                else -> 10
            }
            leftAxis.setLabelCount(yLabelCount, true)
            leftAxis.granularity = yGranularityFt / 3.28084f
            leftAxis.axisMinimum = yMin - (yGranularityFt / 3.28084f) * 0.1f // small padding
            leftAxis.axisMaximum = yMax + (yGranularityFt / 3.28084f) * 0.1f

            chart.notifyDataSetChanged()
            chart.invalidate()

            // Add elevation summary
            val summaryLayout = LinearLayout(context)
            summaryLayout.orientation = LinearLayout.VERTICAL
            summaryLayout.setPadding(0, 16, 0, 0)
            summaryLayout.setBackgroundColor(Color.argb(30, 128, 128, 128))
            summaryLayout.setPadding(16, 12, 16, 12)

            val totalDistance = UnitManager.metersToDistance(cumulativeDistance.toDouble(), context)
            val elevationRange = UnitManager.metersToElevation((maxElevation - minElevation).toDouble(), context)
            val avgElevation = UnitManager.metersToElevation(((minElevation + maxElevation) / 2).toDouble(), context)
            val samplingStr = UnitManager.metersToInterval(samplingDistance, context)
            val summaryText = TextView(context)
            summaryText.text = context.getString(R.string.elevation_summary_format, totalDistance, elevationRange, avgElevation, samplingStr)
            summaryText.textSize = 12f
            summaryText.setTextColor(textColor)
            summaryText.typeface = android.graphics.Typeface.MONOSPACE
            summaryLayout.addView(summaryText)

            layout.addView(summaryLayout)
        }

        layout.addView(chart)
        return layout
    }
} 