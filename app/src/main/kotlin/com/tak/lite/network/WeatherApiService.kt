package com.tak.lite.network

import android.util.Log
import com.tak.lite.data.model.WeatherResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class WeatherApiService {
    companion object {
        private const val TAG = "WeatherApiService"
        private const val BASE_URL = "https://api.openweathermap.org/data/3.0/onecall"
    }
    
    suspend fun getWeatherForecast(
        lat: Double,
        lon: Double,
        apiKey: String,
        units: String = "imperial",
        exclude: String = ""
    ): WeatherResponse = withContext(Dispatchers.IO) {
        val urlString = "$BASE_URL?lat=$lat&lon=$lon&appid=$apiKey&units=$units${if (exclude.isNotEmpty()) "&exclude=$exclude" else ""}"
        Log.d(TAG, "Making request to: $urlString")
        
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            
            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()
                
                val jsonResponse = response.toString()
                Log.d(TAG, "Weather API response received")
                
                // Parse JSON response manually
                parseWeatherResponse(jsonResponse)
            } else {
                Log.e(TAG, "Weather API request failed with code: $responseCode")
                throw Exception("Weather API request failed with code: $responseCode")
            }
        } finally {
            connection.disconnect()
        }
    }
    
    private fun parseWeatherResponse(jsonString: String): WeatherResponse {
        val json = JSONObject(jsonString)
        
        return WeatherResponse(
            lat = json.getDouble("lat"),
            lon = json.getDouble("lon"),
            timezone = json.getString("timezone"),
            timezone_offset = json.getInt("timezone_offset"),
            current = parseCurrentWeather(json.getJSONObject("current")),
            minutely = if (json.has("minutely")) parseMinutelyForecast(json.getJSONArray("minutely")) else null,
            hourly = parseHourlyForecast(json.getJSONArray("hourly")),
            daily = parseDailyForecast(json.getJSONArray("daily")),
            alerts = if (json.has("alerts")) parseAlerts(json.getJSONArray("alerts")) else null
        )
    }
    
    private fun parseCurrentWeather(json: JSONObject): com.tak.lite.data.model.CurrentWeather {
        return com.tak.lite.data.model.CurrentWeather(
            dt = json.getLong("dt"),
            sunrise = json.getLong("sunrise"),
            sunset = json.getLong("sunset"),
            temp = json.getDouble("temp"),
            feels_like = json.getDouble("feels_like"),
            pressure = json.getInt("pressure"),
            humidity = json.getInt("humidity"),
            dew_point = json.getDouble("dew_point"),
            uvi = json.getDouble("uvi"),
            clouds = json.getInt("clouds"),
            visibility = json.getInt("visibility"),
            wind_speed = json.getDouble("wind_speed"),
            wind_deg = json.getInt("wind_deg"),
            wind_gust = if (json.has("wind_gust")) json.getDouble("wind_gust") else null,
            weather = parseWeatherDescription(json.getJSONArray("weather"))
        )
    }
    
    private fun parseHourlyForecast(jsonArray: org.json.JSONArray): List<com.tak.lite.data.model.HourlyForecast> {
        val list = mutableListOf<com.tak.lite.data.model.HourlyForecast>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            list.add(com.tak.lite.data.model.HourlyForecast(
                dt = json.getLong("dt"),
                temp = json.getDouble("temp"),
                feels_like = json.getDouble("feels_like"),
                pressure = json.getInt("pressure"),
                humidity = json.getInt("humidity"),
                dew_point = json.getDouble("dew_point"),
                uvi = json.getDouble("uvi"),
                clouds = json.getInt("clouds"),
                visibility = json.getInt("visibility"),
                wind_speed = json.getDouble("wind_speed"),
                wind_deg = json.getInt("wind_deg"),
                wind_gust = if (json.has("wind_gust")) json.getDouble("wind_gust") else null,
                weather = parseWeatherDescription(json.getJSONArray("weather")),
                pop = json.getDouble("pop")
            ))
        }
        return list
    }
    
    private fun parseDailyForecast(jsonArray: org.json.JSONArray): List<com.tak.lite.data.model.DailyForecast> {
        val list = mutableListOf<com.tak.lite.data.model.DailyForecast>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            list.add(com.tak.lite.data.model.DailyForecast(
                dt = json.getLong("dt"),
                sunrise = json.getLong("sunrise"),
                sunset = json.getLong("sunset"),
                moonrise = json.getLong("moonrise"),
                moonset = json.getLong("moonset"),
                moon_phase = json.getDouble("moon_phase"),
                summary = json.getString("summary"),
                temp = parseTemperature(json.getJSONObject("temp")),
                feels_like = parseFeelsLike(json.getJSONObject("feels_like")),
                pressure = json.getInt("pressure"),
                humidity = json.getInt("humidity"),
                dew_point = json.getDouble("dew_point"),
                wind_speed = json.getDouble("wind_speed"),
                wind_deg = json.getInt("wind_deg"),
                wind_gust = if (json.has("wind_gust")) json.getDouble("wind_gust") else null,
                weather = parseWeatherDescription(json.getJSONArray("weather")),
                clouds = json.getInt("clouds"),
                pop = json.getDouble("pop"),
                rain = if (json.has("rain")) json.getDouble("rain") else null,
                uvi = json.getDouble("uvi")
            ))
        }
        return list
    }
    
    private fun parseTemperature(json: JSONObject): com.tak.lite.data.model.Temperature {
        return com.tak.lite.data.model.Temperature(
            day = json.getDouble("day"),
            min = json.getDouble("min"),
            max = json.getDouble("max"),
            night = json.getDouble("night"),
            eve = json.getDouble("eve"),
            morn = json.getDouble("morn")
        )
    }
    
    private fun parseFeelsLike(json: JSONObject): com.tak.lite.data.model.FeelsLike {
        return com.tak.lite.data.model.FeelsLike(
            day = json.getDouble("day"),
            night = json.getDouble("night"),
            eve = json.getDouble("eve"),
            morn = json.getDouble("morn")
        )
    }
    
    private fun parseWeatherDescription(jsonArray: org.json.JSONArray): List<com.tak.lite.data.model.WeatherDescription> {
        val list = mutableListOf<com.tak.lite.data.model.WeatherDescription>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            list.add(com.tak.lite.data.model.WeatherDescription(
                id = json.getInt("id"),
                main = json.getString("main"),
                description = json.getString("description"),
                icon = json.getString("icon")
            ))
        }
        return list
    }
    
    private fun parseAlerts(jsonArray: org.json.JSONArray): List<com.tak.lite.data.model.WeatherAlert> {
        val list = mutableListOf<com.tak.lite.data.model.WeatherAlert>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            val tags = mutableListOf<String>()
            if (json.has("tags")) {
                val tagsArray = json.getJSONArray("tags")
                for (j in 0 until tagsArray.length()) {
                    tags.add(tagsArray.getString(j))
                }
            }
            list.add(com.tak.lite.data.model.WeatherAlert(
                sender_name = json.getString("sender_name"),
                event = json.getString("event"),
                start = json.getLong("start"),
                end = json.getLong("end"),
                description = json.getString("description"),
                tags = tags
            ))
        }
        return list
    }

    private fun parseMinutelyForecast(jsonArray: org.json.JSONArray): List<com.tak.lite.data.model.MinutelyForecast> {
        val list = mutableListOf<com.tak.lite.data.model.MinutelyForecast>()
        for (i in 0 until jsonArray.length()) {
            val json = jsonArray.getJSONObject(i)
            list.add(com.tak.lite.data.model.MinutelyForecast(
                dt = json.getLong("dt"),
                precipitation = json.getDouble("precipitation")
            ))
        }
        return list
    }
}
