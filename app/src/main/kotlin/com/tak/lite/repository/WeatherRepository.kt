package com.tak.lite.repository

import android.content.Context
import android.util.Log
import com.tak.lite.BuildConfig
import com.tak.lite.data.model.WeatherResponse
import com.tak.lite.data.model.WeatherUiState
import com.tak.lite.network.WeatherApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
// Standard Android networking - no additional dependencies
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherRepository @Inject constructor(
    private val context: Context
) {
    private val TAG = "WeatherRepository"
    private val prefs = context.getSharedPreferences("weather_prefs", Context.MODE_PRIVATE)
    
    private val _weatherState = MutableStateFlow(WeatherUiState())
    val weatherState: StateFlow<WeatherUiState> = _weatherState.asStateFlow()
    
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private val weatherApiService = WeatherApiService()
    
    private var lastLocationLat: Double = 0.0
    private var lastLocationLon: Double = 0.0
    private var lastFetchTime: Long = 0
    private val CACHE_DURATION_MS = TimeUnit.MINUTES.toMillis(15) // 15 minutes
    private val MIN_FETCH_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15) // 15 minutes minimum between fetches
    
    fun fetchWeatherData(latitude: Double, longitude: Double) {
        val currentTime = System.currentTimeMillis()
        val locationKey = "$latitude,$longitude"
        
        // Check if we have recent data for this location
        val isSameLocation = lastLocationLat == latitude && lastLocationLon == longitude
        val isRecentData = currentTime - lastFetchTime < CACHE_DURATION_MS
        val hasData = _weatherState.value.weatherData != null
        
        // Check minimum fetch interval to prevent rapid API calls
        val timeSinceLastFetch = currentTime - lastFetchTime
        if (timeSinceLastFetch < MIN_FETCH_INTERVAL_MS) {
            Log.d(TAG, "Skipping weather fetch - too soon since last fetch (${timeSinceLastFetch}ms < ${MIN_FETCH_INTERVAL_MS}ms)")
            return
        }
        
        if (isSameLocation && isRecentData && hasData) {
            Log.d(TAG, "Using cached weather data for location: $locationKey")
            return
        }
        
        // Check if API key is available
        val apiKey = BuildConfig.OPENWEATHERFORECAST_API_KEY
        if (apiKey.isBlank()) {
            Log.w(TAG, "OpenWeatherMap API key not configured")
            _weatherState.value = _weatherState.value.copy(
                isLoading = false,
                error = "Weather API not configured"
            )
            return
        }
        
        Log.d(TAG, "Fetching weather data for location: $locationKey")
        _weatherState.value = _weatherState.value.copy(isLoading = true, error = null)
        
        repositoryScope.launch {
            try {
                val weatherResponse = withContext(Dispatchers.IO) {
                    weatherApiService.getWeatherForecast(
                        lat = latitude,
                        lon = longitude,
                        apiKey = apiKey
                    )
                }
                
                lastLocationLat = latitude
                lastLocationLon = longitude
                lastFetchTime = currentTime
                
                _weatherState.value = WeatherUiState(
                    isLoading = false,
                    weatherData = weatherResponse,
                    lastUpdated = currentTime
                )
                
                Log.d(TAG, "Weather data fetched successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching weather data", e)
                _weatherState.value = _weatherState.value.copy(
                    isLoading = false,
                    error = "Failed to load weather data: ${e.message}"
                )
            }
        }
    }
    
    fun refreshWeatherData() {
        if (lastLocationLat != 0.0 && lastLocationLon != 0.0) {
            lastFetchTime = 0 // Force refresh
            fetchWeatherData(lastLocationLat, lastLocationLon)
        }
    }
    
    fun clearWeatherData() {
        _weatherState.value = WeatherUiState()
        lastLocationLat = 0.0
        lastLocationLon = 0.0
        lastFetchTime = 0
    }
    
    fun isWeatherEnabled(): Boolean {
        return prefs.getBoolean("weather_enabled", false)
    }
    
    fun setWeatherEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("weather_enabled", enabled).apply()
        if (!enabled) {
            clearWeatherData()
        }
    }
}
