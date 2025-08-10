package com.tak.lite.network

/**
 * Abstraction for building weather radar tile URL templates.
 * Implementations can provide provider-specific templates and optional timeline support.
 */
interface WeatherTileProvider {
    /**
     * Return a URL template for the latest radar tiles.
     * Expected format includes {z}/{x}/{y} placeholders.
     * Return null if not available (e.g., no API key configured).
     */
    fun latestRadarUrlTemplate(weatherSource: String = "precipitation_new"): String?

    /**
     * Return a URL template for a time-specific radar frame, if supported by the provider.
     * Return null if not supported.
     */
    fun timeboxedRadarUrlTemplate(epochMillis: Long, weatherSource: String = "precipitation_new"): String? = latestRadarUrlTemplate(weatherSource)
}

/**
 * Provider for OpenWeatherMap radar tiles (current 1h radar).
 * Returns a raster tile URL template suitable for MapLibre RasterSource.
 */
class OwmWeatherTileProvider(
    private val apiKey: String
) : WeatherTileProvider {
    override fun latestRadarUrlTemplate(weatherSource: String): String? {
        if (apiKey.isBlank()) return null
        // OpenWeatherMap tiles endpoint with configurable weather source
        return "https://tile.openweathermap.org/map/$weatherSource/{z}/{x}/{y}.png?appid=$apiKey"
    }
}


