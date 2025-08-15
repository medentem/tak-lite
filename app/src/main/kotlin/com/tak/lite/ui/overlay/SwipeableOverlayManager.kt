package com.tak.lite.ui.overlay

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import com.tak.lite.R
import com.tak.lite.data.model.WeatherUiState
import com.tak.lite.repository.WeatherRepository
import com.tak.lite.util.BillingManager
import com.tak.lite.util.LocationUtils
import com.tak.lite.util.UnitManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Singleton
class SwipeableOverlayManager @Inject constructor(
    private val context: Context,
    private val weatherRepository: WeatherRepository,
    private val billingManager: BillingManager
) {
    private val TAG = "SwipeableOverlayManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    private lateinit var overlayContainer: LinearLayout
    private lateinit var pageIndicator1: View
    private lateinit var pageIndicator2: View
    private lateinit var directionOverlayContainer: LinearLayout
    private lateinit var weatherOverlayContainer: LinearLayout
    
    // User's current location for relative positioning
    private var userLatitude: Double = 0.0
    private var userLongitude: Double = 0.0
    private var hasUserLocation: Boolean = false
    
    // Direction overlay views
    private lateinit var directionOverlayView: View
    private lateinit var degreeText: android.widget.TextView
    private lateinit var headingSourceText: android.widget.TextView
    private lateinit var speedText: android.widget.TextView
    private lateinit var speedUnitsText: android.widget.TextView
    private lateinit var altitudeText: android.widget.TextView
    private lateinit var altitudeUnitsText: android.widget.TextView
    private lateinit var latLngText: android.widget.TextView
    private lateinit var compassCardinalView: com.tak.lite.ui.location.CompassCardinalView
    private lateinit var compassQualityIndicator: android.widget.ImageView
    private lateinit var compassQualityText: android.widget.TextView
    private lateinit var calibrationIndicator: android.widget.ImageView
    private lateinit var detailsContainer: LinearLayout
    
    // Weather overlay view
    private lateinit var weatherOverlayView: WeatherOverlayView
    
    private var currentPage = 0
    private val totalPages = 2
    
    // Callback for when views are ready
    private var onViewsReadyCallback: (() -> Unit)? = null
    
    // Callback for when weather page is opened
    private var onWeatherPageOpenedCallback: (() -> Unit)? = null
    
    /**
     * Update the user's current location for relative positioning
     */
    fun updateUserLocation(latitude: Double, longitude: Double) {
        userLatitude = latitude
        userLongitude = longitude
        hasUserLocation = true
        Log.d(TAG, "Updated user location: ($latitude, $longitude)")
    }
    
    fun initialize(container: LinearLayout) {
        overlayContainer = container
        pageIndicator1 = container.findViewById(R.id.pageIndicator1)
        pageIndicator2 = container.findViewById(R.id.pageIndicator2)
        directionOverlayContainer = container.findViewById(R.id.directionOverlayContainer)
        weatherOverlayContainer = container.findViewById(R.id.weatherOverlayContainer)
        
        setupCustomSwipeableOverlay()
        setupPageIndicators()
        
        // Show first page by default
        showPage(0)
    }
    
    private fun setupCustomSwipeableOverlay() {
        // Initialize direction overlay
        directionOverlayView = LayoutInflater.from(context).inflate(R.layout.direction_overlay, directionOverlayContainer, false)
        directionOverlayContainer.addView(directionOverlayView)
        
        // Initialize direction overlay views
        degreeText = directionOverlayView.findViewById(R.id.degreeText)
        headingSourceText = directionOverlayView.findViewById(R.id.headingSourceText)
        speedText = directionOverlayView.findViewById(R.id.speedText)
        speedUnitsText = directionOverlayView.findViewById(R.id.speedUnitsText)
        altitudeText = directionOverlayView.findViewById(R.id.altitudeText)
        altitudeUnitsText = directionOverlayView.findViewById(R.id.altitudeUnitsText)
        latLngText = directionOverlayView.findViewById(R.id.latLngText)
        compassCardinalView = directionOverlayView.findViewById(R.id.compassCardinalView)
        compassQualityIndicator = directionOverlayView.findViewById(R.id.compassQualityIndicator)
        compassQualityText = directionOverlayView.findViewById(R.id.compassQualityText)
        calibrationIndicator = directionOverlayView.findViewById(R.id.calibrationIndicator)
        detailsContainer = directionOverlayView.findViewById(R.id.detailsContainer)
        
        // Initialize weather overlay
        weatherOverlayView = WeatherOverlayView(context)
        weatherOverlayContainer.addView(weatherOverlayView)
        
        // Set up weather observers
        weatherOverlayView.setupWeatherObservers()
        
        // Set up gesture detection for swiping
        setupGestureDetection()
        
        // Notify that views are ready
        onViewsReadyCallback?.invoke()
    }
    
    private fun setupGestureDetection() {
        var isHorizontalGesture = false
        var switchedThisGesture = false
        val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                isHorizontalGesture = false
                switchedThisGesture = false
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null) return false
                
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                if (abs(diffX) > abs(diffY)) {
                    isHorizontalGesture = true
                    
                    // Attempt to switch page if threshold met
                    if (abs(diffX) > 60 && !switchedThisGesture) {
                        if (diffX > 0 && currentPage > 0) {
                            showPage(currentPage - 1)
                            switchedThisGesture = true
                        } else if (diffX < 0 && currentPage < totalPages - 1) {
                            showPage(currentPage + 1)
                            switchedThisGesture = true
                        }
                    }
                    return true  // Consume all horizontal scrolls
                }
                return false
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                
                val diffX = e2.x - e1.x
                val diffY = e2.y - e1.y
                
                if (abs(diffX) > abs(diffY) && abs(diffX) > 60) {
                    if (diffX > 0) {
                        if (currentPage > 0) {
                            showPage(currentPage - 1)
                            return true
                        }
                    } else {
                        if (currentPage < totalPages - 1) {
                            showPage(currentPage + 1)
                            return true
                        }
                    }
                    return true  // Consume fling even if no page change
                }
                return false
            }
        })
        
        fun attachSwipeListener(view: View) {
            view.setOnTouchListener { _, event ->
                val handled = gestureDetector.onTouchEvent(event)
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    isHorizontalGesture = false
                }
                // Only consume if we actually handled a horizontal gesture, not just any touch
                handled && isHorizontalGesture
            }
        }

        fun attachRecursively(v: View) {
            attachSwipeListener(v)
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    attachRecursively(v.getChildAt(i))
                }
            }
        }

        // Attach to root and both pages to ensure all touches are captured
        attachRecursively(overlayContainer)
    }
    
    private fun showPage(pageIndex: Int) {
        if (pageIndex == 1 && !billingManager.isPremium()) {
            showPage(0)
            return
        }
        currentPage = pageIndex
        updatePageIndicators()
        
        when (pageIndex) {
            0 -> {
                directionOverlayContainer.visibility = View.VISIBLE
                weatherOverlayContainer.visibility = View.GONE
            }
            1 -> {
                directionOverlayContainer.visibility = View.GONE
                weatherOverlayContainer.visibility = View.VISIBLE
                onWeatherPageOpenedCallback?.invoke()
            }
        }
        
        Log.d(TAG, "Page changed to: $pageIndex")
    }
    
    private fun setupPageIndicators() {
        updatePageIndicators()
    }
    
    private fun updatePageIndicators() {
        pageIndicator1.setBackgroundResource(
            if (currentPage == 0) R.drawable.circle_blue else R.drawable.circle_gray
        )
        if (billingManager.isPremium()) {
            pageIndicator2.visibility = View.VISIBLE
            pageIndicator2.setBackgroundResource(
                if (currentPage == 1) R.drawable.circle_blue else R.drawable.circle_gray
            )
        } else {
            pageIndicator2.visibility = View.GONE
        }
    }
    
    fun fetchWeatherData(latitude: Double, longitude: Double) {
        weatherRepository.fetchWeatherData(latitude, longitude)
    }
    
    // Getter methods for direction overlay views
    fun getDegreeText(): android.widget.TextView = degreeText
    fun getHeadingSourceText(): android.widget.TextView = headingSourceText
    fun getSpeedText(): android.widget.TextView = speedText
    fun getSpeedUnitsText(): android.widget.TextView = speedUnitsText
    fun getAltitudeText(): android.widget.TextView = altitudeText
    fun getAltitudeUnitsText(): android.widget.TextView = altitudeUnitsText
    fun getLatLngText(): android.widget.TextView = latLngText
    fun getCompassCardinalView(): com.tak.lite.ui.location.CompassCardinalView = compassCardinalView
    fun getCompassQualityIndicator(): android.widget.ImageView = compassQualityIndicator
    fun getCompassQualityText(): android.widget.TextView = compassQualityText
    fun getCalibrationIndicator(): android.widget.ImageView = calibrationIndicator
    fun getDetailsContainer(): LinearLayout = detailsContainer
    fun getDirectionOverlay(): View = directionOverlayView
    
    fun setOnViewsReadyCallback(callback: () -> Unit) {
        onViewsReadyCallback = callback
        // If views are already initialized, call the callback immediately
        if (::directionOverlayView.isInitialized) {
            callback()
        }
    }
    
    fun setOnWeatherPageOpenedCallback(callback: () -> Unit) {
        onWeatherPageOpenedCallback = callback
    }
    
    private inner class WeatherOverlayView(context: Context) : LinearLayout(context) {
        
        // Header views
        private lateinit var weatherAgeText: android.widget.TextView
        private lateinit var refreshButton: android.widget.ImageButton
        
        // Tab views
        private lateinit var tabCurrent: android.widget.TextView
        private lateinit var tabMinute: android.widget.TextView
        private lateinit var tabHourly: android.widget.TextView
        private lateinit var tabDaily: android.widget.TextView
        private lateinit var tabAlerts: android.widget.TextView
        
        // Section views
        private lateinit var currentWeatherSection: LinearLayout
        private lateinit var weatherDetailsSection: LinearLayout
        private lateinit var minutelyForecastSection: LinearLayout
        private lateinit var hourlyForecastSection: LinearLayout
        private lateinit var dailyForecastSection: LinearLayout
        private lateinit var alertsSection: LinearLayout
        
        // Current weather views
        private lateinit var currentTempText: android.widget.TextView
        private lateinit var weatherDescriptionText: android.widget.TextView
        private lateinit var feelsLikeText: android.widget.TextView
        private lateinit var humidityValueText: android.widget.TextView
        private lateinit var windSpeedText: android.widget.TextView
        private lateinit var uvIndexText: android.widget.TextView
        private lateinit var pressureText: android.widget.TextView
        private lateinit var sunriseText: android.widget.TextView
        private lateinit var sunsetText: android.widget.TextView
        
        // Forecast containers
        private lateinit var minutelyForecastContainer: LinearLayout
        private lateinit var hourlyForecastContainer: LinearLayout
        private lateinit var dailyForecastContainer: LinearLayout
        private lateinit var alertsContainer: LinearLayout
        
        // Loading/Error views
        private lateinit var loadingContainer: LinearLayout
        private lateinit var errorText: android.widget.TextView
        
        private var currentWeatherTab = 0 // 0=Current, 1=Minute, 2=Hourly, 3=Daily, 4=Alerts
        private var refreshSpinAnimator: ObjectAnimator? = null
        
        init {
            setupView()
        }
        
        private fun setupView() {
            val inflater = LayoutInflater.from(context)
            inflater.inflate(R.layout.weather_overlay, this, true)
            
            // Initialize header views
            weatherAgeText = findViewById(R.id.weatherAgeText)
            refreshButton = findViewById(R.id.refreshButton)
            
            // Initialize tab views
            tabCurrent = findViewById(R.id.tabCurrent)
            tabMinute = findViewById(R.id.tabMinute)
            tabHourly = findViewById(R.id.tabHourly)
            tabDaily = findViewById(R.id.tabDaily)
            tabAlerts = findViewById(R.id.tabAlerts)
            
            // Initialize section views
            currentWeatherSection = findViewById(R.id.currentWeatherSection)
            weatherDetailsSection = findViewById(R.id.weatherDetailsSection)
            minutelyForecastSection = findViewById(R.id.minutelyForecastSection)
            hourlyForecastSection = findViewById(R.id.hourlyForecastSection)
            dailyForecastSection = findViewById(R.id.dailyForecastSection)
            alertsSection = findViewById(R.id.alertsSection)
            
            // Initialize current weather views
            currentTempText = findViewById(R.id.currentTempText)
            weatherDescriptionText = findViewById(R.id.weatherDescriptionText)
            feelsLikeText = findViewById(R.id.feelsLikeText)
            humidityValueText = findViewById(R.id.humidityValueText)
            windSpeedText = findViewById(R.id.windSpeedText)
            uvIndexText = findViewById(R.id.uvIndexText)
            pressureText = findViewById(R.id.pressureText)
            sunriseText = findViewById(R.id.sunriseText)
            sunsetText = findViewById(R.id.sunsetText)
            
            // Initialize forecast containers
            minutelyForecastContainer = findViewById(R.id.minutelyForecastContainer)
            hourlyForecastContainer = findViewById(R.id.hourlyForecastContainer)
            dailyForecastContainer = findViewById(R.id.dailyForecastContainer)
            alertsContainer = findViewById(R.id.alertsContainer)
            
            // Initialize loading/error views
            loadingContainer = findViewById(R.id.loadingContainer)
            errorText = findViewById(R.id.errorText)
            
            setupTabListeners()
            setupRefreshButton()
            showWeatherTab(0) // Show current weather by default
        }
        
        private fun setupTabListeners() {
            tabCurrent.setOnClickListener { showWeatherTab(0) }
            tabMinute.setOnClickListener { showWeatherTab(1) }
            tabHourly.setOnClickListener { showWeatherTab(2) }
            tabDaily.setOnClickListener { showWeatherTab(3) }
            tabAlerts.setOnClickListener { showWeatherTab(4) }
        }
        
        private fun setupRefreshButton() {
            refreshButton.setOnClickListener {
                weatherRepository.refreshWeatherData()
            }
        }
        
        private fun showWeatherTab(tabIndex: Int) {
            currentWeatherTab = tabIndex
            
            // Update tab colors
            updateTabColors()
            
            // Show/hide sections based on selected tab
            currentWeatherSection.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            weatherDetailsSection.visibility = if (tabIndex == 0) View.VISIBLE else View.GONE
            minutelyForecastSection.visibility = if (tabIndex == 1) View.VISIBLE else View.GONE
            hourlyForecastSection.visibility = if (tabIndex == 2) View.VISIBLE else View.GONE
            dailyForecastSection.visibility = if (tabIndex == 3) View.VISIBLE else View.GONE
            alertsSection.visibility = if (tabIndex == 4) View.VISIBLE else View.GONE
            
            // Refresh forecast data for the selected tab if we have weather data
            weatherRepository.weatherState.value.weatherData?.let { weatherData ->
                when (tabIndex) {
                    1 -> updateMinutelyForecast(weatherData.minutely?.take(60) ?: emptyList())
                    2 -> updateHourlyForecast(weatherData.hourly.take(8))
                    3 -> updateDailyForecast(weatherData.daily.take(7))
                    4 -> updateAlerts(weatherData.alerts ?: emptyList())
                }
            }
        }
        
        private fun updateTabColors() {
            val activeColor = Color.parseColor("#F0F0F0")
            val inactiveColor = Color.parseColor("#B0B0B0")
            
            tabCurrent.setTextColor(if (currentWeatherTab == 0) activeColor else inactiveColor)
            tabMinute.setTextColor(if (currentWeatherTab == 1) activeColor else inactiveColor)
            tabHourly.setTextColor(if (currentWeatherTab == 2) activeColor else inactiveColor)
            tabDaily.setTextColor(if (currentWeatherTab == 3) activeColor else inactiveColor)
            tabAlerts.setTextColor(if (currentWeatherTab == 4) activeColor else inactiveColor)
        }
        
        fun setupWeatherObservers() {
            scope.launch {
                weatherRepository.weatherState.collectLatest { weatherState ->
                    updateWeatherUI(weatherState)
                }
            }
        }
        
        private fun updateWeatherUI(weatherState: WeatherUiState) {
            when {
                weatherState.isLoading -> {
                    // Hide bottom loading UI in favor of spinning refresh button
                    loadingContainer.visibility = View.GONE
                    errorText.visibility = View.GONE
                    startRefreshSpinner()
                    refreshButton.isEnabled = false
                    // Clear data while loading
                    clearWeatherData()
                }
                weatherState.error != null -> {
                    loadingContainer.visibility = View.GONE
                    errorText.visibility = View.VISIBLE
                    errorText.text = weatherState.error
                    stopRefreshSpinner()
                    refreshButton.isEnabled = true
                    // Clear data on error
                    clearWeatherData()
                }
                weatherState.weatherData != null -> {
                    loadingContainer.visibility = View.GONE
                    errorText.visibility = View.GONE
                    displayWeatherData(weatherState.weatherData)
                    updateAgeIndicator(weatherState.lastUpdated)
                    stopRefreshSpinner()
                    refreshButton.isEnabled = true
                }
                else -> {
                    // No data available yet
                    loadingContainer.visibility = View.GONE
                    errorText.visibility = View.GONE
                    stopRefreshSpinner()
                    refreshButton.isEnabled = true
                    clearWeatherData()
                }
            }
        }

        private fun startRefreshSpinner() {
            if (refreshSpinAnimator?.isRunning == true) return
            refreshSpinAnimator = ObjectAnimator.ofFloat(refreshButton, View.ROTATION, 0f, 360f).apply {
                duration = 800
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }

        private fun stopRefreshSpinner() {
            refreshSpinAnimator?.cancel()
            refreshSpinAnimator = null
            // Reset rotation so the icon rests upright
            refreshButton.rotation = 0f
        }
        
        private fun updateAgeIndicator(lastUpdated: Long) {
            if (lastUpdated > 0) {
                val currentTime = System.currentTimeMillis()
                val ageMinutes = (currentTime - lastUpdated) / (1000 * 60)
                weatherAgeText.text = "from $ageMinutes min ago"
            } else {
                weatherAgeText.text = context.getString(R.string.no_data_short)
            }
        }
        
        private fun clearWeatherData() {
            currentTempText.text = "--"
            weatherDescriptionText.text = "--"
            feelsLikeText.text = "--"
            humidityValueText.text = "--"
            windSpeedText.text = "--"
            minutelyForecastContainer.removeAllViews()
            hourlyForecastContainer.removeAllViews()
            dailyForecastContainer.removeAllViews()
            alertsContainer.removeAllViews()
        }
        
        private fun displayWeatherData(weatherData: com.tak.lite.data.model.WeatherResponse) {
            val current = weatherData.current
            
            // Update current weather with null safety
            currentTempText.text = formatTemp(current.temp)
            weatherDescriptionText.text = current.weather.firstOrNull()?.description?.capitalize() ?: "--"
            feelsLikeText.text = formatFeelsLike(current.feels_like)
            
            // Update details with null safety
            humidityValueText.text = if (current.humidity != null) "${current.humidity}%" else "--"
            windSpeedText.text = if (current.wind_speed != null) formatWindSpeed(current.wind_speed) else "--"
            uvIndexText.text = if (current.uvi != null) String.format("UV %.1f", current.uvi) else "--"
            pressureText.text = if (current.pressure != null) formatPressure(current.pressure) else "--"
            sunriseText.text = formatTime(weatherData.timezone_offset, current.sunrise)
            sunsetText.text = formatTime(weatherData.timezone_offset, current.sunset)

            // Optional: weather icons can be added later if assets exist
            
            // Update forecasts based on current tab
            when (currentWeatherTab) {
                1 -> updateMinutelyForecast(weatherData.minutely?.take(60) ?: emptyList())
                2 -> updateHourlyForecast(weatherData.hourly.take(8)) // Show next 8 hours
                3 -> updateDailyForecast(weatherData.daily.take(7)) // Show 7 days
                4 -> updateAlerts(weatherData.alerts ?: emptyList())
            }

            // Display relative location description
            val locationDescription = if (hasUserLocation) {
                // Debug location calculations if needed (uncomment to enable)
                // LocationUtils.debugLocationCalculations(userLatitude, userLongitude, weatherData.lat, weatherData.lon)
                
                LocationUtils.getRelativeLocationDescription(
                    weatherData.lat, 
                    weatherData.lon, 
                    userLatitude, 
                    userLongitude,
                    context
                )
            } else {
                context.getString(R.string.forecast_for_your_location)
            }
            findViewById<android.widget.TextView>(R.id.cityText).text = locationDescription
        }
        
        private fun updateMinutelyForecast(minutelyData: List<com.tak.lite.data.model.MinutelyForecast>) {
            minutelyForecastContainer.removeAllViews()
            
            minutelyData.forEachIndexed { index, minutely ->
                val inflater = LayoutInflater.from(context)
                val itemView = inflater.inflate(R.layout.minutely_forecast_item, minutelyForecastContainer, false)
                
                val minuteText = itemView.findViewById<android.widget.TextView>(R.id.minuteText)
                val precipitationBar = itemView.findViewById<View>(R.id.precipitationBar)
                val precipitationValue = itemView.findViewById<android.widget.TextView>(R.id.precipitationValue)
                
                // Format time (every 5 minutes)
                val minutes = index // API is per-minute buckets starting at 0m
                minuteText.text = "${minutes}m"
                
                // Set precipitation bar height based on precipitation value
                val height = (minutely.precipitation * 80).toInt().coerceAtLeast(3)
                val layoutParams = precipitationBar.layoutParams
                layoutParams.height = (height * context.resources.displayMetrics.density).toInt()
                precipitationBar.layoutParams = layoutParams
                
                // Set color based on precipitation intensity
                val color = when {
                    minutely.precipitation > 0.5 -> Color.parseColor("#2196F3") // Blue
                    minutely.precipitation > 0.1 -> Color.parseColor("#4CAF50") // Green
                    else -> Color.parseColor("#B0B0B0") // Gray
                }
                precipitationBar.setBackgroundColor(color)
                
                // Show precipitation value (only if > 0)
                if (minutely.precipitation > 0) {
                    precipitationValue.text = formatPrecipitation(minutely.precipitation)
                    precipitationValue.visibility = View.VISIBLE
                } else {
                    precipitationValue.visibility = View.GONE
                }
                
                minutelyForecastContainer.addView(itemView)
            }
        }
        
        private fun updateHourlyForecast(hourlyData: List<com.tak.lite.data.model.HourlyForecast>) {
            hourlyForecastContainer.removeAllViews()
            
            hourlyData.forEach { hourly ->
                val inflater = LayoutInflater.from(context)
                val itemView = inflater.inflate(R.layout.hourly_forecast_item, hourlyForecastContainer, false)
                
                val hourText = itemView.findViewById<android.widget.TextView>(R.id.hourText)
                val tempText = itemView.findViewById<android.widget.TextView>(R.id.hourlyTempText)
                val popText = itemView.findViewById<android.widget.TextView>(R.id.hourlyPopText)
                
                // Format time
                val time = formatHour(weatherRepository.weatherState.value.weatherData?.timezone_offset, hourly.dt)
                hourText.text = time
                
                // Handle null values safely
                tempText.text = formatTemp(hourly.temp)
                popText.text = if (hourly.pop != null) "${(hourly.pop * 100).toInt()}%" else "--"
                
                hourlyForecastContainer.addView(itemView)
            }
        }
        
        private fun updateDailyForecast(dailyData: List<com.tak.lite.data.model.DailyForecast>) {
            dailyForecastContainer.removeAllViews()
            
            dailyData.forEach { daily ->
                val inflater = LayoutInflater.from(context)
                val itemView = inflater.inflate(R.layout.daily_forecast_item, dailyForecastContainer, false)
                
                val dayText = itemView.findViewById<android.widget.TextView>(R.id.dayText)
                val dailyHighText = itemView.findViewById<android.widget.TextView>(R.id.dailyHighText)
                val dailyLowText = itemView.findViewById<android.widget.TextView>(R.id.dailyLowText)
                val dailyPopText = itemView.findViewById<android.widget.TextView>(R.id.dailyPopText)
                
                // Format day
                val day = formatDay(weatherRepository.weatherState.value.weatherData?.timezone_offset, daily.dt)
                dayText.text = day
                
                // Handle null values safely
                dailyHighText.text = formatTemp(daily.temp.max)
                dailyLowText.text = formatTemp(daily.temp.min)
                dailyPopText.text = if (daily.pop != null) "${(daily.pop * 100).toInt()}%" else "--"
                
                dailyForecastContainer.addView(itemView)
            }
        }
        
        private fun updateAlerts(alertsData: List<com.tak.lite.data.model.WeatherAlert>) {
            alertsContainer.removeAllViews()
            
            if (alertsData.isEmpty()) {
                val noAlertsText = android.widget.TextView(context).apply {
                    text = context.getString(R.string.no_weather_alerts_short)
                    textSize = 12f
                    setTextColor(Color.parseColor("#B0B0B0"))
                    gravity = android.view.Gravity.CENTER
                    setPadding(16, 16, 16, 16)
                }
                alertsContainer.addView(noAlertsText)
                return
            }
            
            alertsData.forEach { alert ->
                val inflater = LayoutInflater.from(context)
                val itemView = inflater.inflate(R.layout.weather_alert_item, alertsContainer, false)
                
                val alertEventText = itemView.findViewById<android.widget.TextView>(R.id.alertEventText)
                val alertTimeText = itemView.findViewById<android.widget.TextView>(R.id.alertTimeText)
                val alertDescriptionText = itemView.findViewById<android.widget.TextView>(R.id.alertDescriptionText)
                
                alertEventText.text = alert.event
                
                // Format time
                val endTime = formatFull(weatherRepository.weatherState.value.weatherData?.timezone_offset, alert.end)
                alertTimeText.text = "Until $endTime"
                
                // Truncate description if too long
                val description = if (alert.description.length > 150) {
                    alert.description.substring(0, 147) + "..."
                } else {
                    alert.description
                }
                alertDescriptionText.text = description
                
                alertsContainer.addView(itemView)
            }
        }
        
        private fun String.capitalize(): String {
            return if (isNotEmpty()) {
                this[0].uppercase() + substring(1)
            } else {
                this
            }
        }

        private fun formatTemp(temperature: Double?): String {
            if (temperature == null) return "--"
            // API now provides temperature in the user's preferred units
            // Imperial: Fahrenheit, Metric: Celsius
            return "${temperature.toInt()}°"
        }

        private fun formatFeelsLike(value: Double?): String {
            if (value == null) return "--"
            return "Feels like ${formatTemp(value)}"
        }
        
        private fun formatWindSpeed(windSpeed: Double): String {
            // API provides wind speed in user's preferred units
            // Imperial: mph, Metric: m/s
            val unitSystem = UnitManager.getUnitSystem(context)
            return when (unitSystem) {
                UnitManager.UnitSystem.IMPERIAL -> "${windSpeed.toInt()} mph"
                UnitManager.UnitSystem.METRIC -> "${windSpeed.toInt()} m/s"
            }
        }
        
        private fun formatPressure(pressure: Int): String {
            // API provides pressure in hPa (metric) regardless of unit system
            // For imperial users, we could convert to inHg, but hPa is standard in meteorology
            return "$pressure hPa"
        }
        
        private fun formatPrecipitation(precipitation: Double): String {
            // API provides precipitation in mm (metric) regardless of unit system
            // For imperial users, convert to inches
            val unitSystem = UnitManager.getUnitSystem(context)
            return when (unitSystem) {
                UnitManager.UnitSystem.IMPERIAL -> {
                    val inches = precipitation / 25.4 // mm to inches
                    String.format("%.2f in", inches)
                }
                UnitManager.UnitSystem.METRIC -> {
                    String.format("%.1f mm", precipitation)
                }
            }
        }

        private fun formatTime(offsetSeconds: Int, epochSeconds: Long): String {
            val tz = java.util.TimeZone.getTimeZone("GMT")
            val df = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
            df.timeZone = tz
            val adjusted = (epochSeconds + offsetSeconds).coerceAtLeast(0) * 1000
            return df.format(java.util.Date(adjusted))
        }

        private fun formatHour(offsetSeconds: Int?, epochSeconds: Long): String {
            val df = java.text.SimpleDateFormat("h a", java.util.Locale.getDefault())
            val tz = java.util.TimeZone.getTimeZone("GMT")
            df.timeZone = tz
            val adjusted = ((epochSeconds + (offsetSeconds ?: 0)).coerceAtLeast(0)) * 1000
            return df.format(java.util.Date(adjusted))
        }

        private fun formatDay(offsetSeconds: Int?, epochSeconds: Long): String {
            val df = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
            val tz = java.util.TimeZone.getTimeZone("GMT")
            df.timeZone = tz
            val adjusted = ((epochSeconds + (offsetSeconds ?: 0)).coerceAtLeast(0)) * 1000
            return df.format(java.util.Date(adjusted))
        }

        private fun formatFull(offsetSeconds: Int?, epochSeconds: Long): String {
            val df = java.text.SimpleDateFormat("MMM dd, h:mm a", java.util.Locale.getDefault())
            val tz = java.util.TimeZone.getTimeZone("GMT")
            df.timeZone = tz
            val adjusted = ((epochSeconds + (offsetSeconds ?: 0)).coerceAtLeast(0)) * 1000
            return df.format(java.util.Date(adjusted))
        }
    }
}
