package com.tak.lite.data.model

import com.google.gson.annotations.SerializedName

/**
 * Data models for TAK Lite server communication
 */

data class LoginRequest(
    val email: String,
    val password: String
)

data class LoginResponse(
    val token: String,
    val refreshToken: String? = null
)

data class UserInfo(
    val userId: String,
    val email: String,
    val name: String,
    val isAdmin: Boolean
)

data class Team(
    val id: String,
    val name: String,
    @SerializedName("created_at")
    val createdAt: String? = null
)

data class ServerError(
    val error: String,
    val message: String? = null
)

data class ServerResponse<T>(
    val success: Boolean? = null,
    val data: T? = null,
    val error: String? = null
)
