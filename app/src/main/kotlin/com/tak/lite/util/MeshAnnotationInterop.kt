package com.tak.lite.util

import android.util.Log
import com.geeksville.mesh.ATAKProtos
import com.geeksville.mesh.MeshProtos
import com.google.protobuf.ByteString
import com.tak.lite.model.MapAnnotation
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
        chatMessage: String? = null
    ): MeshProtos.Data {
        Log.d(TAG, "mapAnnotationToMeshData called with annotation=$annotation, nickname=$nickname, groupRole=$groupRole, groupTeam=$groupTeam, batteryLevel=$batteryLevel, pliLatitude=$pliLatitude, pliLongitude=$pliLongitude, pliAltitude=$pliAltitude, pliSpeed=$pliSpeed, pliCourse=$pliCourse, chatMessage=$chatMessage")
        val jsonString = Json.encodeToString(annotation)
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
                .setLatitudeI((pliLatitude * 1e7).toInt())
                .setLongitudeI((pliLongitude * 1e7).toInt())
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
            val annotation = Json.decodeFromString<MapAnnotation>(jsonString)
            Log.d(TAG, "Deserialized MapAnnotation: $annotation")
            annotation
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
        val pliBuilder = ATAKProtos.PLI.newBuilder()
            .setLatitudeI((pliLatitude * 1e7).toInt())
            .setLongitudeI((pliLongitude * 1e7).toInt())
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
} 