package com.tak.lite.util

import android.util.Log
import com.geeksville.mesh.ATAKProtos
import com.geeksville.mesh.MeshProtos
import com.google.protobuf.ByteString
import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LineStyle
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

// TODO: There should be protobufs for this stuff. We can PR that to the official meshtastic firmware later
object MeshAnnotationInterop {
    private const val TAG = "MeshAnnotationInterop"

    fun mapAnnotationToMeshData(
        annotation: MapAnnotation,
        nickname: String? = null,
        groupRole: ATAKProtos.MemberRole? = null,
        groupTeam: ATAKProtos.Team? = null,
        batteryLevel: Int? = null,
        pliLatitude: Double? = null,
        pliLongitude: Double? = null,
        pliAltitude: Int? = null,
        pliSpeed: Int? = null,
        pliCourse: Int? = null,
        chatMessage: String? = null,
        channel: Int? = null
    ): MeshProtos.Data {
        Log.d(TAG, "mapAnnotationToMeshData called with annotation=$annotation, nickname=$nickname, groupRole=$groupRole, groupTeam=$groupTeam, batteryLevel=$batteryLevel, pliLatitude=$pliLatitude, pliLongitude=$pliLongitude, pliAltitude=$pliAltitude, pliSpeed=$pliSpeed, pliCourse=$pliCourse, chatMessage=$chatMessage")
        val jsonString = when (annotation) {
            is MapAnnotation.PointOfInterest -> {
                // Minified JSON for POI
                val colorShort = when (annotation.color) {
                    AnnotationColor.GREEN -> "g"
                    AnnotationColor.YELLOW -> "y"
                    AnnotationColor.RED -> "r"
                    AnnotationColor.BLACK -> "b"
                    AnnotationColor.WHITE -> "w"
                }
                val shapeShort = when (annotation.shape) {
                    PointShape.CIRCLE -> "c"
                    PointShape.EXCLAMATION -> "e"
                    PointShape.SQUARE -> "s"
                    PointShape.TRIANGLE -> "t"
                }
                val pos = annotation.position
                val map = mutableMapOf<String, Any>(
                    "t" to "poi",
                    "i" to annotation.id,
                    "c" to annotation.creatorId,
                    "ts" to annotation.timestamp,
                    "cl" to colorShort,
                    "p" to mapOf(
                        "a" to (pos.lt * 1e5).toLong(),
                        "o" to (pos.lng * 1e5).toLong()
                    ),
                    "s" to shapeShort
                )
                annotation.expirationTime?.let { map["e"] = it }
                annotation.label?.let { map["l"] = it }
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject(map.mapValues { (k, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Map<*, *> -> {
                            val m = v as Map<String, Any>
                            kotlinx.serialization.json.JsonObject(m.mapValues { (_, vv) ->
                                when (vv) {
                                    is String -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    is Number -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    else -> kotlinx.serialization.json.JsonNull
                                }
                            })
                        }
                        else -> kotlinx.serialization.json.JsonNull
                    }
                }))
            }
            is MapAnnotation.Line -> {
                val colorShort = when (annotation.color) {
                    AnnotationColor.GREEN -> "g"
                    AnnotationColor.YELLOW -> "y"
                    AnnotationColor.RED -> "r"
                    AnnotationColor.BLACK -> "b"
                    AnnotationColor.WHITE -> "w"
                }
                val styleShort = when (annotation.style) {
                    LineStyle.SOLID -> "s"
                    LineStyle.DASHED -> "d"
                }
                // Delta encoding with 5 decimal places
                val absPoints = annotation.points.map { pt ->
                    listOf(
                        (pt.lt * 1e5).toLong(),
                        (pt.lng * 1e5).toLong()
                    )
                }
                val pointsArr = if (absPoints.isNotEmpty()) {
                    val deltas = mutableListOf<List<Long>>()
                    deltas.add(absPoints[0]) // first point absolute
                    for (i in 1 until absPoints.size) {
                        val prev = absPoints[i - 1]
                        val curr = absPoints[i]
                        deltas.add(listOf(curr[0] - prev[0], curr[1] - prev[1]))
                    }
                    deltas
                } else {
                    emptyList()
                }
                val map = mutableMapOf<String, Any>(
                    "t" to "line",
                    "i" to annotation.id,
                    "c" to annotation.creatorId,
                    "ts" to annotation.timestamp,
                    "cl" to colorShort,
                    "pts" to pointsArr,
                    "st" to styleShort,
                    "ah" to if (annotation.arrowHead) 1 else 0
                )
                annotation.expirationTime?.let { map["e"] = it }
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject(map.mapValues { (k, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { pt ->
                            val arr = pt as List<Long>
                            kotlinx.serialization.json.JsonArray(arr.map { n ->
                                kotlinx.serialization.json.JsonPrimitive(n)
                            })
                        })
                        is Map<*, *> -> {
                            val m = v as Map<String, Any>
                            kotlinx.serialization.json.JsonObject(m.mapValues { (_, vv) ->
                                when (vv) {
                                    is String -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    is Number -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    else -> kotlinx.serialization.json.JsonNull
                                }
                            })
                        }
                        else -> kotlinx.serialization.json.JsonNull
                    }
                }))
            }
            is MapAnnotation.Area -> {
                val colorShort = when (annotation.color) {
                    AnnotationColor.GREEN -> "g"
                    AnnotationColor.YELLOW -> "y"
                    AnnotationColor.RED -> "r"
                    AnnotationColor.BLACK -> "b"
                    else -> "g"
                }
                val center = annotation.center
                val map = mutableMapOf<String, Any>(
                    "t" to "area",
                    "i" to annotation.id,
                    "c" to annotation.creatorId,
                    "ts" to annotation.timestamp,
                    "cl" to colorShort,
                    "ctr" to mapOf(
                        "a" to (center.lt * 1e5).toLong(),
                        "o" to (center.lng * 1e5).toLong()
                    ),
                    "r" to annotation.radius
                )
                annotation.expirationTime?.let { map["e"] = it }
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject(map.mapValues { (k, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Map<*, *> -> {
                            val m = v as Map<String, Any>
                            kotlinx.serialization.json.JsonObject(m.mapValues { (_, vv) ->
                                when (vv) {
                                    is String -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    is Number -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    else -> kotlinx.serialization.json.JsonNull
                                }
                            })
                        }
                        else -> kotlinx.serialization.json.JsonNull
                    }
                }))
            }
            is MapAnnotation.Polygon -> {
                val colorShort = when (annotation.color) {
                    AnnotationColor.GREEN -> "g"
                    AnnotationColor.YELLOW -> "y"
                    AnnotationColor.RED -> "r"
                    AnnotationColor.BLACK -> "b"
                    AnnotationColor.WHITE -> "w"
                }
                
                // Send all polygon points - the closing point will be added during rendering
                val pointsForTransmission = annotation.points
                
                // Delta encoding with 5 decimal places (same as lines)
                val absPoints = pointsForTransmission.map { pt ->
                    listOf(
                        (pt.lt * 1e5).toLong(),
                        (pt.lng * 1e5).toLong()
                    )
                }
                val pointsArr = if (absPoints.isNotEmpty()) {
                    val deltas = mutableListOf<List<Long>>()
                    deltas.add(absPoints[0]) // first point absolute
                    for (i in 1 until absPoints.size) {
                        val prev = absPoints[i - 1]
                        val curr = absPoints[i]
                        deltas.add(listOf(curr[0] - prev[0], curr[1] - prev[1]))
                    }
                    deltas
                } else {
                    emptyList()
                }
                val map = mutableMapOf<String, Any>(
                    "t" to "polygon",
                    "i" to annotation.id,
                    "c" to annotation.creatorId,
                    "ts" to annotation.timestamp,
                    "cl" to colorShort,
                    "pts" to pointsArr,
                    "fo" to annotation.fillOpacity,
                    "sw" to annotation.strokeWidth
                )
                annotation.expirationTime?.let { map["e"] = it }
                annotation.label?.let { map["l"] = it }
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject(map.mapValues { (k, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        is List<*> -> kotlinx.serialization.json.JsonArray(v.map { pt ->
                            val arr = pt as List<Long>
                            kotlinx.serialization.json.JsonArray(arr.map { n ->
                                kotlinx.serialization.json.JsonPrimitive(n)
                            })
                        })
                        is Map<*, *> -> {
                            val m = v as Map<String, Any>
                            kotlinx.serialization.json.JsonObject(m.mapValues { (_, vv) ->
                                when (vv) {
                                    is String -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    is Number -> kotlinx.serialization.json.JsonPrimitive(vv)
                                    else -> kotlinx.serialization.json.JsonNull
                                }
                            })
                        }
                        else -> kotlinx.serialization.json.JsonNull
                    }
                }))
            }
            is MapAnnotation.Deletion -> {
                val map = mutableMapOf<String, Any>(
                    "t" to "del",
                    "i" to annotation.id,
                    "c" to annotation.creatorId,
                    "ts" to annotation.timestamp,
                    "cl" to "r"
                )
                annotation.expirationTime?.let { map["e"] = it }
                kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject(map.mapValues { (k, v) ->
                    when (v) {
                        is String -> kotlinx.serialization.json.JsonPrimitive(v)
                        is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                        else -> kotlinx.serialization.json.JsonNull
                    }
                }))
            }
            else -> Json.encodeToString(annotation)
        }
        Log.d(TAG, "Serialized annotation to JSON: $jsonString")
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
            .setDetail(ByteString.copyFrom(jsonString.toByteArray(Charsets.UTF_8)))
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
            Log.d(TAG, "Set contact with callsign: $nickname")
        }
        if (groupRole != null || groupTeam != null) {
            val groupBuilder = ATAKProtos.Group.newBuilder()
            groupRole?.let { groupBuilder.role = it }
            groupTeam?.let { groupBuilder.team = it }
            takPacketBuilder.group = groupBuilder.build()
            Log.d(TAG, "Set group with role: $groupRole, team: $groupTeam")
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
            Log.d(TAG, "Set status with battery: $batteryLevel")
        }
        if (pliLatitude != null && pliLongitude != null) {
            val pliBuilder = ATAKProtos.PLI.newBuilder()
                .setLatitudeI((pliLatitude * 1e5).toInt())
                .setLongitudeI((pliLongitude * 1e5).toInt())
            pliAltitude?.let { pliBuilder.altitude = it }
            pliSpeed?.let { pliBuilder.speed = it }
            pliCourse?.let { pliBuilder.course = it }
            takPacketBuilder.pli = pliBuilder.build()
            Log.d(TAG, "Set PLI with lat: $pliLatitude, lon: $pliLongitude, alt: $pliAltitude, speed: $pliSpeed, course: $pliCourse")
        }
        if (!chatMessage.isNullOrBlank()) {
            takPacketBuilder.chat = ATAKProtos.GeoChat.newBuilder().setMessage(chatMessage).build()
            Log.d(TAG, "Set chat message: $chatMessage")
        }
        if (channel != null) {
            takPacketBuilder
            Log.d(TAG, "Set channel: $channel")
        }
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
        Log.d(TAG, "Built MeshProtos.Data: portnum=${data.portnum}, payload size=${data.payload.size()}")
        return data
    }

    fun meshDataToMapAnnotation(data: MeshProtos.Data): MapAnnotation? {
        Log.d(TAG, "meshDataToMapAnnotation called with portnum=${data.portnum}, payload size=${data.payload.size()}")
        return try {
            if (data.portnum != com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN) {
                Log.w(TAG, "Portnum does not match ATAK_PLUGIN, returning null")
                return null
            }
            val takPacket = ATAKProtos.TAKPacket.parseFrom(data.payload)
            val jsonString = takPacket.detail.toStringUtf8()
            Log.d(TAG, "Extracted JSON from TAKPacket: $jsonString")
            // Try to detect minified POI/Line/Area/Deletion
            val json = kotlinx.serialization.json.Json.parseToJsonElement(jsonString)
            if (json is kotlinx.serialization.json.JsonObject) {
                when (json["t"]?.jsonPrimitive?.contentOrNull) {
                    "poi" -> {
                        val id = json["i"]?.jsonPrimitive?.content ?: return null
                        val creatorId = json["c"]?.jsonPrimitive?.content ?: "local"
                        val timestamp = json["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val colorShort = json["cl"]?.jsonPrimitive?.content ?: "g"
                        val color = when (colorShort) {
                            "g" -> AnnotationColor.GREEN
                            "y" -> AnnotationColor.YELLOW
                            "r" -> AnnotationColor.RED
                            "b" -> AnnotationColor.BLACK
                            else -> AnnotationColor.GREEN
                        }
                        val posObj = json["p"]?.jsonObject
                        // Integer decoding with 5 decimal places
                        val lat = posObj?.get("a")?.jsonPrimitive?.longOrNull?.toDouble()?.div(1e5) ?: 0.0
                        val lon = posObj?.get("o")?.jsonPrimitive?.longOrNull?.toDouble()?.div(1e5) ?: 0.0
                        val shapeShort = json["s"]?.jsonPrimitive?.content ?: "c"
                        val shape = when (shapeShort) {
                            "c" -> PointShape.CIRCLE
                            "e" -> PointShape.EXCLAMATION
                            "s" -> PointShape.SQUARE
                            "t" -> PointShape.TRIANGLE
                            else -> PointShape.CIRCLE
                        }
                        val expirationTime = json["e"]?.jsonPrimitive?.longOrNull
                        val label = json["l"]?.jsonPrimitive?.content
                        MapAnnotation.PointOfInterest(
                            id = id,
                            creatorId = creatorId,
                            timestamp = timestamp,
                            color = color,
                            position = com.tak.lite.model.LatLngSerializable(lat, lon),
                            shape = shape,
                            label = label,
                            expirationTime = expirationTime
                        )
                    }
                    "line" -> {
                        val id = json["i"]?.jsonPrimitive?.content ?: return null
                        val creatorId = json["c"]?.jsonPrimitive?.content ?: "local"
                        val timestamp = json["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val colorShort = json["cl"]?.jsonPrimitive?.content ?: "g"
                        val color = when (colorShort) {
                            "g" -> AnnotationColor.GREEN
                            "y" -> AnnotationColor.YELLOW
                            "r" -> AnnotationColor.RED
                            "b" -> AnnotationColor.BLACK
                            else -> AnnotationColor.GREEN
                        }
                        // Delta decoding with 5 decimal places
                        val pointsArr = json["pts"]?.jsonArray?.let { arr ->
                            val decoded = mutableListOf<com.tak.lite.model.LatLngSerializable>()
                            var lastLat: Long? = null
                            var lastLon: Long? = null
                            for ((i, pt) in arr.withIndex()) {
                                val pair = pt.jsonArray
                                val lat = pair[0].jsonPrimitive.longOrNull ?: 0L
                                val lon = pair[1].jsonPrimitive.longOrNull ?: 0L
                                if (i == 0) {
                                    // first point is absolute
                                    decoded.add(com.tak.lite.model.LatLngSerializable(lat.toDouble() / 1e5, lon.toDouble() / 1e5))
                                    lastLat = lat
                                    lastLon = lon
                                } else {
                                    // delta from previous
                                    val newLat = (lastLat ?: 0L) + lat
                                    val newLon = (lastLon ?: 0L) + lon
                                    decoded.add(com.tak.lite.model.LatLngSerializable(newLat.toDouble() / 1e5, newLon.toDouble() / 1e5))
                                    lastLat = newLat
                                    lastLon = newLon
                                }
                            }
                            decoded
                        } ?: emptyList()
                        val styleShort = json["st"]?.jsonPrimitive?.content ?: "s"
                        val style = when (styleShort) {
                            "s" -> LineStyle.SOLID
                            "d" -> LineStyle.DASHED
                            else -> LineStyle.SOLID
                        }
                        val arrowHead = json["ah"]?.jsonPrimitive?.intOrNull == 1
                        val expirationTime = json["e"]?.jsonPrimitive?.longOrNull
                        MapAnnotation.Line(
                            id = id,
                            creatorId = creatorId,
                            timestamp = timestamp,
                            color = color,
                            points = pointsArr,
                            style = style,
                            arrowHead = arrowHead,
                            expirationTime = expirationTime
                        )
                    }
                    "area" -> {
                        val id = json["i"]?.jsonPrimitive?.content ?: return null
                        val creatorId = json["c"]?.jsonPrimitive?.content ?: "local"
                        val timestamp = json["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val colorShort = json["cl"]?.jsonPrimitive?.content ?: "g"
                        val color = when (colorShort) {
                            "g" -> AnnotationColor.GREEN
                            "y" -> AnnotationColor.YELLOW
                            "r" -> AnnotationColor.RED
                            "b" -> AnnotationColor.BLACK
                            else -> AnnotationColor.GREEN
                        }
                        val centerObj = json["ctr"]?.jsonObject
                        // Integer decoding with 5 decimal places
                        val lat = centerObj?.get("a")?.jsonPrimitive?.longOrNull?.toDouble()?.div(1e5) ?: 0.0
                        val lon = centerObj?.get("o")?.jsonPrimitive?.longOrNull?.toDouble()?.div(1e5) ?: 0.0
                        val radius = json["r"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val expirationTime = json["e"]?.jsonPrimitive?.longOrNull
                        MapAnnotation.Area(
                            id = id,
                            creatorId = creatorId,
                            timestamp = timestamp,
                            color = color,
                            center = com.tak.lite.model.LatLngSerializable(lat, lon),
                            radius = radius,
                            expirationTime = expirationTime
                        )
                    }
                    "polygon" -> {
                        val id = json["i"]?.jsonPrimitive?.content ?: return null
                        val creatorId = json["c"]?.jsonPrimitive?.content ?: "local"
                        val timestamp = json["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val colorShort = json["cl"]?.jsonPrimitive?.content ?: "g"
                        val color = when (colorShort) {
                            "g" -> AnnotationColor.GREEN
                            "y" -> AnnotationColor.YELLOW
                            "r" -> AnnotationColor.RED
                            "b" -> AnnotationColor.BLACK
                            else -> AnnotationColor.GREEN
                        }
                        // Delta decoding with 5 decimal places
                        val pointsArr = json["pts"]?.jsonArray?.let { arr ->
                            val decoded = mutableListOf<com.tak.lite.model.LatLngSerializable>()
                            var lastLat: Long? = null
                            var lastLon: Long? = null
                            for ((i, pt) in arr.withIndex()) {
                                val pair = pt.jsonArray
                                val lat = pair[0].jsonPrimitive.longOrNull ?: 0L
                                val lon = pair[1].jsonPrimitive.longOrNull ?: 0L
                                if (i == 0) {
                                    // first point is absolute
                                    decoded.add(com.tak.lite.model.LatLngSerializable(lat.toDouble() / 1e5, lon.toDouble() / 1e5))
                                    lastLat = lat
                                    lastLon = lon
                                } else {
                                    // delta from previous
                                    val newLat = (lastLat ?: 0L) + lat
                                    val newLon = (lastLon ?: 0L) + lon
                                    decoded.add(com.tak.lite.model.LatLngSerializable(newLat.toDouble() / 1e5, newLon.toDouble() / 1e5))
                                    lastLat = newLat
                                    lastLon = newLon
                                }
                            }
                            decoded
                        } ?: emptyList()
                        val fillOpacity = json["fo"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val strokeWidth = json["sw"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                        val expirationTime = json["e"]?.jsonPrimitive?.longOrNull
                        val label = json["l"]?.jsonPrimitive?.content
                        MapAnnotation.Polygon(
                            id = id,
                            creatorId = creatorId,
                            timestamp = timestamp,
                            color = color,
                            points = pointsArr,
                            fillOpacity = fillOpacity.toFloat(),
                            strokeWidth = strokeWidth.toFloat(),
                            expirationTime = expirationTime,
                            label = label
                        )
                    }
                    "del" -> {
                        val id = json["i"]?.jsonPrimitive?.content ?: return null
                        val creatorId = json["c"]?.jsonPrimitive?.content ?: "local"
                        val timestamp = json["ts"]?.jsonPrimitive?.longOrNull ?: System.currentTimeMillis()
                        val expirationTime = json["e"]?.jsonPrimitive?.longOrNull
                        MapAnnotation.Deletion(
                            id = id,
                            creatorId = creatorId,
                            timestamp = timestamp,
                            expirationTime = expirationTime
                        )
                    }
                    else -> Json.decodeFromString<MapAnnotation>(jsonString)
                }
            } else {
                Json.decodeFromString<MapAnnotation>(jsonString)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in meshDataToMapAnnotation", e)
            null
        }
    }

    fun mapLocationToMeshData(
        nickname: String? = null,
        batteryLevel: Int? = null,
        pliLatitude: Double,
        pliLongitude: Double,
        pliAltitude: Int? = null,
        pliSpeed: Int? = null,
        pliCourse: Int? = null
    ): MeshProtos.Data {
        Log.d(TAG, "mapLocationToMeshData called with nickname=$nickname, batteryLevel=$batteryLevel, pliLatitude=$pliLatitude, pliLongitude=$pliLongitude, pliAltitude=$pliAltitude, pliSpeed=$pliSpeed, pliCourse=$pliCourse")
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
            Log.d(TAG, "Set contact with callsign: $nickname")
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
            Log.d(TAG, "Set status with battery: $batteryLevel")
        }
        // Integer encoding with 5 decimal places for PLI
        val pliBuilder = ATAKProtos.PLI.newBuilder()
            .setLatitudeI((pliLatitude * 1e5).toInt())
            .setLongitudeI((pliLongitude * 1e5).toInt())
        pliAltitude?.let { pliBuilder.altitude = it }
        pliSpeed?.let { pliBuilder.speed = it }
        pliCourse?.let { pliBuilder.course = it }
        takPacketBuilder.pli = pliBuilder.build()
        Log.d(TAG, "Set PLI with lat: $pliLatitude, lon: $pliLongitude, alt: $pliAltitude, speed: $pliSpeed, course: $pliCourse")
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.POSITION_APP)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
        Log.d(TAG, "Built MeshProtos.Data: portnum=${data.portnum}, payload size=${data.payload.size()}")
        return data
    }

    // --- Bulk Deletion Interop ---

    /**
     * Create a MeshProtos.Data for a bulk deletion of annotation IDs, using a compact JSON array of IDs in the detail field.
     */
    fun bulkDeleteToMeshData(
        ids: List<String>,
        nickname: String? = null,
        batteryLevel: Int? = null
    ): MeshProtos.Data {
        // Use a compact JSON array of IDs for space efficiency
        val jsonArray = Json.encodeToString(ids)
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
            .setDetail(ByteString.copyFrom(jsonArray.toByteArray(Charsets.UTF_8)))
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
        }
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
        return data
    }

    /**
     * Create a MeshProtos.Data for a user status update
     */
    fun statusUpdateToMeshData(
        status: com.tak.lite.model.UserStatus,
        nickname: String? = null,
        batteryLevel: Int? = null
    ): MeshProtos.Data {
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
        
        // Use the proper serializable data class for status updates
        val statusUpdate = com.tak.lite.model.UserStatusUpdate(
            userId = "local", // Will be replaced by actual user ID if available
            status = status,
            timestamp = System.currentTimeMillis()
        )
        val statusJson = Json.encodeToString(statusUpdate)
        takPacketBuilder.detail = ByteString.copyFrom(statusJson.toByteArray(Charsets.UTF_8))
        
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
        }
        
        val data = MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
        
        Log.d(TAG, "Built status update MeshProtos.Data: status=$status, payload size=${data.payload.size()}")
        return data
    }

    /**
     * Try to parse a status update from a MeshProtos.Data. Returns UserStatus if it's a status message, else null.
     */
    fun meshDataToStatusUpdate(data: MeshProtos.Data): com.tak.lite.model.UserStatus? {
        if (data.portnum != com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN) return null
        
        return try {
            val takPacket = ATAKProtos.TAKPacket.parseFrom(data.payload)
            val jsonString = takPacket.detail.toStringUtf8()
            
            // Try to parse as UserStatusUpdate first
            try {
                val statusUpdate = Json.decodeFromString<com.tak.lite.model.UserStatusUpdate>(jsonString)
                return statusUpdate.status
            } catch (e: Exception) {
                // Fallback to old format for backward compatibility
                val parsed = Json.parseToJsonElement(jsonString)
                if (parsed is JsonObject && parsed["type"]?.jsonPrimitive?.content == "status") {
                    val statusName = parsed["status"]?.jsonPrimitive?.content
                    if (statusName != null) {
                        com.tak.lite.model.UserStatus.valueOf(statusName)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "Failed to parse status update: ${e.message}")
            null
        }
    }

    /**
     * Try to parse a bulk deletion from a MeshProtos.Data. Returns a list of IDs if the detail is a JSON array of strings, else null.
     */
    fun meshDataToBulkDeleteIds(data: MeshProtos.Data): List<String>? {
        if (data.portnum != com.geeksville.mesh.Portnums.PortNum.ATAK_PLUGIN) return null
        return try {
            val takPacket = ATAKProtos.TAKPacket.parseFrom(data.payload)
            val jsonString = takPacket.detail.toStringUtf8()
            // Try to parse as a JSON array of strings
            val parsed = Json.parseToJsonElement(jsonString)
            if (parsed is JsonArray) {
                parsed.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
} 