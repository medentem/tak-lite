package com.tak.lite.model

import com.google.android.gms.maps.model.LatLng
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
sealed class MapAnnotation {
    abstract val id: String
    abstract val creatorId: String
    abstract val timestamp: Long
    abstract val color: AnnotationColor
    
    @Serializable
    @SerialName("poi")
    data class PointOfInterest(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val position: LatLngSerializable,
        val shape: PointShape
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("line")
    data class Line(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val points: List<LatLngSerializable>
    ) : MapAnnotation()
    
    @Serializable
    @SerialName("area")
    data class Area(
        override val id: String = UUID.randomUUID().toString(),
        override val creatorId: String,
        override val timestamp: Long = System.currentTimeMillis(),
        override val color: AnnotationColor,
        val center: LatLngSerializable,
        val radius: Double // in meters
    ) : MapAnnotation()
}

@Serializable
enum class AnnotationColor {
    @SerialName("green") GREEN,
    @SerialName("yellow") YELLOW,
    @SerialName("red") RED,
    @SerialName("black") BLACK
}

@Serializable
enum class PointShape {
    @SerialName("circle") CIRCLE,
    @SerialName("exclamation") EXCLAMATION
}

@Serializable
data class LatLngSerializable(
    val latitude: Double,
    val longitude: Double
) {
    fun toGoogleLatLng(): com.google.android.gms.maps.model.LatLng {
        return com.google.android.gms.maps.model.LatLng(latitude, longitude)
    }
    
    companion object {
        fun fromGoogleLatLng(latLng: com.google.android.gms.maps.model.LatLng): LatLngSerializable {
            return LatLngSerializable(latLng.latitude, latLng.longitude)
        }
    }
} 