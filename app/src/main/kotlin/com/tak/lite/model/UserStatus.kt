package com.tak.lite.model

import kotlinx.serialization.Serializable

@Serializable
enum class UserStatus {
    RED, YELLOW, BLUE, ORANGE, VIOLET, GREEN
}

@Serializable
data class UserStatusUpdate(
    val userId: String,
    val status: UserStatus,
    val timestamp: Long = System.currentTimeMillis()
)

fun UserStatus.toColor(): Int {
    return when (this) {
        UserStatus.RED -> android.graphics.Color.parseColor("#F44336") // Material red 500
        UserStatus.YELLOW -> android.graphics.Color.parseColor("#FFEB3B") // Material yellow 500
        UserStatus.BLUE -> android.graphics.Color.parseColor("#2196F3") // Material blue 500
        UserStatus.ORANGE -> android.graphics.Color.parseColor("#FF5722") // Material deep orange 500
        UserStatus.VIOLET -> android.graphics.Color.parseColor("#E1BEE7") // Material purple 100
        UserStatus.GREEN -> android.graphics.Color.parseColor("#4CAF50") // Material green 500
    }
}

fun UserStatus.toDisplayName(): String {
    return when (this) {
        UserStatus.RED -> "Red"
        UserStatus.YELLOW -> "Yellow"
        UserStatus.BLUE -> "Blue"
        UserStatus.ORANGE -> "Orange"
        UserStatus.VIOLET -> "Violet"
        UserStatus.GREEN -> "Green"
    }
} 