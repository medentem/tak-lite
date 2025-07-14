package com.tak.lite.model

import kotlinx.serialization.Serializable

@Serializable
enum class UserStatus {
    GREEN, YELLOW, RED
}

@Serializable
data class UserStatusUpdate(
    val userId: String,
    val status: UserStatus,
    val timestamp: Long = System.currentTimeMillis()
)

fun UserStatus.toColor(): Int {
    return when (this) {
        UserStatus.GREEN -> android.graphics.Color.parseColor("#4CAF50") // Material green 500
        UserStatus.YELLOW -> android.graphics.Color.parseColor("#FFC107") // Material amber 500
        UserStatus.RED -> android.graphics.Color.parseColor("#F44336") // Material red 500
    }
}

fun UserStatus.toDisplayName(): String {
    return when (this) {
        UserStatus.GREEN -> "Green"
        UserStatus.YELLOW -> "Yellow"
        UserStatus.RED -> "Emergency"
    }
} 