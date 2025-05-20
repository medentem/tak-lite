package com.tak.lite.util

import com.geeksville.mesh.MeshProtos
import com.tak.lite.model.MapAnnotation
import com.google.protobuf.ByteString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import com.geeksville.mesh.ATAKProtos

object MeshAnnotationInterop {
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
        val jsonString = Json.encodeToString(annotation)
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
            .setDetail(ByteString.copyFrom(jsonString.toByteArray(Charsets.UTF_8)))
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
        }
        if (groupRole != null || groupTeam != null) {
            val groupBuilder = ATAKProtos.Group.newBuilder()
            groupRole?.let { groupBuilder.role = it }
            groupTeam?.let { groupBuilder.team = it }
            takPacketBuilder.group = groupBuilder.build()
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
        }
        if (pliLatitude != null && pliLongitude != null) {
            val pliBuilder = ATAKProtos.PLI.newBuilder()
                .setLatitudeI((pliLatitude * 1e7).toInt())
                .setLongitudeI((pliLongitude * 1e7).toInt())
            pliAltitude?.let { pliBuilder.altitude = it }
            pliSpeed?.let { pliBuilder.speed = it }
            pliCourse?.let { pliBuilder.course = it }
            takPacketBuilder.pli = pliBuilder.build()
        }
        if (!chatMessage.isNullOrBlank()) {
            takPacketBuilder.chat = ATAKProtos.GeoChat.newBuilder().setMessage(chatMessage).build()
        }
        return MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.TAK_LITE_APP)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
    }

    fun meshDataToMapAnnotation(data: MeshProtos.Data): MapAnnotation? {
        return try {
            if (data.portnum != com.geeksville.mesh.Portnums.PortNum.TAK_LITE_APP) return null
            val takPacket = ATAKProtos.TAKPacket.parseFrom(data.payload)
            val jsonString = takPacket.detail.toStringUtf8()
            Json.decodeFromString<MapAnnotation>(jsonString)
        } catch (e: Exception) {
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
        val takPacketBuilder = ATAKProtos.TAKPacket.newBuilder()
        if (!nickname.isNullOrBlank()) {
            takPacketBuilder.contact = ATAKProtos.Contact.newBuilder().setCallsign(nickname).build()
        }
        if (batteryLevel != null) {
            takPacketBuilder.status = ATAKProtos.Status.newBuilder().setBattery(batteryLevel).build()
        }
        val pliBuilder = ATAKProtos.PLI.newBuilder()
            .setLatitudeI((pliLatitude * 1e7).toInt())
            .setLongitudeI((pliLongitude * 1e7).toInt())
        pliAltitude?.let { pliBuilder.altitude = it }
        pliSpeed?.let { pliBuilder.speed = it }
        pliCourse?.let { pliBuilder.course = it }
        takPacketBuilder.pli = pliBuilder.build()
        return MeshProtos.Data.newBuilder()
            .setPortnum(com.geeksville.mesh.Portnums.PortNum.POSITION_APP)
            .setPayload(takPacketBuilder.build().toByteString())
            .build()
    }
} 