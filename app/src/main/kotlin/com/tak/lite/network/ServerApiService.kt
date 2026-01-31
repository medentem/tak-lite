package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.tak.lite.data.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Service for communicating with TAK Lite server
 */
class ServerApiService(private val context: Context) {
    
    private val gson: Gson = GsonBuilder().create()
    private val prefs = context.getSharedPreferences("server_prefs", Context.MODE_PRIVATE)
    
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(createAuthInterceptor())
            .addInterceptor(createLoggingInterceptor())
            .build()
    }
    
    private fun createAuthInterceptor(): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val token = getStoredToken()
            
            val newRequest = if (token != null) {
                request.newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                request
            }
            
            chain.proceed(newRequest)
        }
    }
    
    private fun createLoggingInterceptor(): HttpLoggingInterceptor {
        return HttpLoggingInterceptor { message ->
            Log.d("ServerApiService", message)
        }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
    }
    
    /**
     * Login to the server with username and password
     */
    suspend fun login(serverUrl: String, username: String, password: String): Result<LoginResponse> {
        return withContext(Dispatchers.IO) {
            try {
                val requestBody = gson.toJson(LoginRequest(username, password))
                    .toRequestBody("application/json".toMediaType())
                
                val request = Request.Builder()
                    .url("$serverUrl/api/auth/login")
                    .post(requestBody)
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val loginResponse = gson.fromJson(responseBody, LoginResponse::class.java)
                        storeToken(loginResponse.token)
                        storeServerUrl(serverUrl)
                        Result.success(loginResponse)
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            gson.fromJson(errorBody, ServerError::class.java)
                        } catch (e: Exception) {
                            ServerError("Login failed", errorBody)
                        }
                    } else {
                        ServerError("Login failed", "HTTP ${response.code}")
                    }
                    Result.failure(Exception(error.error))
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Login error", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Get current user information
     */
    suspend fun getUserInfo(serverUrl: String): Result<UserInfo> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/auth/whoami")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val userInfo = gson.fromJson(responseBody, UserInfo::class.java)
                        Result.success(userInfo)
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            gson.fromJson(errorBody, ServerError::class.java)
                        } catch (e: Exception) {
                            ServerError("Failed to get user info", errorBody)
                        }
                    } else {
                        ServerError("Failed to get user info", "HTTP ${response.code}")
                    }
                    Result.failure(Exception(error.error))
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Get user info error", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Get user's teams
     */
    suspend fun getUserTeams(serverUrl: String): Result<List<Team>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/auth/user/teams")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val teams = gson.fromJson(responseBody, Array<Team>::class.java).toList()
                        Result.success(teams)
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            gson.fromJson(errorBody, ServerError::class.java)
                        } catch (e: Exception) {
                            ServerError("Failed to get teams", errorBody)
                        }
                    } else {
                        ServerError("Failed to get teams", "HTTP ${response.code}")
                    }
                    Result.failure(Exception(error.error))
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Get teams error", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Logout from the server
     */
    suspend fun logout(serverUrl: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/auth/logout")
                    .post(RequestBody.create(null, ""))
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    clearStoredToken()
                    Result.success(Unit)
                } else {
                    // Even if logout fails on server, clear local token
                    clearStoredToken()
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Logout error", e)
                // Even if logout fails, clear local token
                clearStoredToken()
                Result.success(Unit)
            }
        }
    }
    
    /**
     * Test server connection
     */
    suspend fun testConnection(serverUrl: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/auth/whoami")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.code == 401) {
                    // 401 is expected when not authenticated, but server is reachable
                    Result.success(Unit)
                } else if (response.isSuccessful) {
                    Result.success(Unit)
                } else {
                    Result.failure(Exception("Server returned HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Test connection error", e)
                Result.failure(e)
            }
        }
    }
    
    /**
     * Fetch existing annotations for a team (includes team annotations + global annotations)
     */
    suspend fun getAnnotations(serverUrl: String, teamId: String): Result<List<ServerAnnotation>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/sync/annotations?teamId=$teamId")
                    .get()
                    .build()
                
                val response = client.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    if (responseBody != null) {
                        val annotations = gson.fromJson(responseBody, Array<ServerAnnotation>::class.java).toList()
                        Log.d("ServerApiService", "Fetched ${annotations.size} annotations for team $teamId")
                        Result.success(annotations)
                    } else {
                        Result.failure(Exception("Empty response body"))
                    }
                } else {
                    val errorBody = response.body?.string()
                    val error = if (errorBody != null) {
                        try {
                            gson.fromJson(errorBody, ServerError::class.java)
                        } catch (e: Exception) {
                            ServerError("Failed to fetch annotations", errorBody)
                        }
                    } else {
                        ServerError("Failed to fetch annotations", "HTTP ${response.code}")
                    }
                    Result.failure(Exception(error.error))
                }
            } catch (e: Exception) {
                Log.e("ServerApiService", "Get annotations error", e)
                Result.failure(e)
            }
        }
    }
    
    // Token management
    private fun storeToken(token: String) {
        prefs.edit().putString("server_token", token).apply()
    }
    
    private fun getStoredToken(): String? {
        return prefs.getString("server_token", null)
    }
    
    private fun clearStoredToken() {
        prefs.edit().remove("server_token").apply()
    }
    
    private fun storeServerUrl(url: String) {
        prefs.edit().putString("server_url", url).apply()
    }
    
    fun getStoredServerUrl(): String? {
        return prefs.getString("server_url", null)
    }
    
    fun isLoggedIn(): Boolean {
        return getStoredToken() != null
    }
    
    fun clearAllData() {
        prefs.edit().clear().apply()
    }
}
