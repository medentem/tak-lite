package com.tak.lite.ui.location

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader
import org.json.JSONObject

class MeshRiderGpsController {
    companion object {
        private const val TAG = "MeshRiderGpsController"
        private const val MESH_RIDER_IP = "192.168.1.10"
        private const val GPS_PORT = 2947
    }

    data class GpsData(
        val latitude: Double,
        val longitude: Double,
        val altitude: Double,
        val speed: Float,
        val time: String
    )

    suspend fun getMeshRiderLocation(): Location? = withContext(Dispatchers.IO) {
        try {
            // Try to connect to the Mesh Rider's GPS data
            val url = URL("http://$MESH_RIDER_IP:$GPS_PORT")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2000 // 2 second timeout
            connection.readTimeout = 2000
            connection.requestMethod = "GET"

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val response = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    response.append(line)
                }
                reader.close()

                // Parse the GPS data from the response
                try {
                    val jsonResponse = JSONObject(response.toString())
                    if (jsonResponse.has("class") && jsonResponse.getString("class") == "TPV") {
                        val location = Location("mesh_rider")
                        location.latitude = jsonResponse.getDouble("lat")
                        location.longitude = jsonResponse.getDouble("lon")
                        location.altitude = jsonResponse.getDouble("alt")
                        location.speed = jsonResponse.getDouble("speed").toFloat()
                        location.time = jsonResponse.getLong("time")
                        location.accuracy = 2.5f // From Mesh Rider specs
                        return@withContext location
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing GPS JSON: ${e.message}")
                }
            } else {
                Log.d(TAG, "Failed to connect to Mesh Rider GPS: ${connection.responseCode}")
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Error getting Mesh Rider GPS: ${e.message}")
            null
        }
    }
} 