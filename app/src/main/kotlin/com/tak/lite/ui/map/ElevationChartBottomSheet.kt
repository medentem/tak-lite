package com.tak.lite.ui.map

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.tak.lite.R
import com.tak.lite.model.MapAnnotation
import com.tak.lite.util.getOfflineElevation
import com.tak.lite.util.haversine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ElevationChartBottomSheet(
    private val line: MapAnnotation.Line
) : BottomSheetDialogFragment() {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        android.util.Log.d("ElevationChartBottomSheet", "onCreateView called")
        val context = requireContext()
        val layout = LinearLayout(context)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(0, 0, 0, 0)

        val chart = LineChart(context)
        chart.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            500
        )

        // Detect dark mode
        val isDarkMode = (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bgColor = if (isDarkMode) Color.parseColor("#181A20") else Color.WHITE
        val lineColor = if (isDarkMode) ContextCompat.getColor(context, R.color.primary_dark) else ContextCompat.getColor(context, R.color.primary_light)
        val textColor = if (isDarkMode) Color.parseColor("#B0B3B8") else Color.DKGRAY

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
                return if (value < 0.01f) "0 mi" else String.format("%.2f mi", value / 1609.344f)
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
                return String.format("%.0f ft", value * 3.28084f)
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

            // --- Interpolate more points for smoother chart ---
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
                    val steps = (dist / 30.0).toInt().coerceAtLeast(1)
                    densePoints.addAll(interpolatePoints(p1, p2, steps))
                }
            }

            // --- Sample elevations for all dense points ---
            var cumulativeDistance: Float = 0f
            var prevLat: Double? = null
            var prevLon: Double? = null
            for ((idx, pt) in densePoints.withIndex()) {
                val lat = pt.latitude
                val lon = pt.longitude
                var elevation = getOfflineElevation(lat, lon, zoom, filesDir)
                if (elevation == null && mapController != null) {
                    val n = Math.pow(2.0, zoom.toDouble())
                    val x = ((lon + 180.0) / 360.0 * n).toInt()
                    val latRad = Math.toRadians(lat)
                    val y = ((1.0 - Math.log(Math.tan(latRad) + 1 / Math.cos(latRad)) / Math.PI) / 2.0 * n).toInt()
                    withContext(kotlinx.coroutines.Dispatchers.IO) {
                        mapController.downloadTerrainDemTile(zoom, x, y)
                    }
                    elevation = getOfflineElevation(lat, lon, zoom, filesDir)
                }
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
                override fun refreshContent(e: com.github.mikephil.charting.data.Entry?, highlight: com.github.mikephil.charting.highlight.Highlight?) {
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
                            val text = String.format("%.2f mi\n%.0f ft", d, e)
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
            val xMin: Float = entries.map { it.x }.minOrNull() ?: 0.0f
            val xMax: Float = entries.map { it.x }.maxOrNull() ?: 1.0f
            val yMin: Float = entries.map { it.y }.minOrNull() ?: 0.0f
            val yMax: Float = entries.map { it.y }.maxOrNull() ?: 1.0f
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
        }

        layout.addView(chart)
        return layout
    }

    // Interface for getting MapController from the activity
    interface MapControllerProvider {
        fun getMapController(): MapController?
    }
} 