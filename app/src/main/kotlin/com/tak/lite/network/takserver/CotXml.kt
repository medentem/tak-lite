package com.tak.lite.network.takserver

import com.tak.lite.model.AnnotationColor
import com.tak.lite.model.LatLngSerializable
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PointShape
import com.tak.lite.model.UserStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal CoT XML builder/parser for Meshtastic Local TAK Server interop.
 * Dual-encodes ATAK-visible outer CoT plus optional &lt;taklite&gt; fidelity payload.
 */
object CotXml {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val MESHTASTIC_NODE_UID = Regex("""![0-9a-fA-F]{8,}""")

    fun nowIso(ms: Long = System.currentTimeMillis()): String = isoFormat.format(Date(ms))

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(c)
            }
        }
    }

    fun unescape(text: String): String =
        text.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")

    fun userStatusToGroupName(status: UserStatus): String = when (status) {
        UserStatus.RED -> "Red"
        UserStatus.YELLOW -> "Yellow"
        UserStatus.BLUE -> "Blue"
        UserStatus.ORANGE -> "Orange"
        UserStatus.VIOLET -> "Purple"
        UserStatus.GREEN -> "Green"
    }

    fun groupNameToUserStatus(name: String?): UserStatus? = when (name?.lowercase(Locale.US)) {
        "red" -> UserStatus.RED
        "yellow" -> UserStatus.YELLOW
        "blue" -> UserStatus.BLUE
        "orange" -> UserStatus.ORANGE
        "purple", "violet" -> UserStatus.VIOLET
        "green", "cyan" -> UserStatus.GREEN
        else -> null
    }

    fun annotationColorToCotType(color: AnnotationColor): String = when (color) {
        AnnotationColor.GREEN -> "a-f-G-U-C"
        AnnotationColor.YELLOW -> "a-n-G-U-C"
        AnnotationColor.RED -> "a-h-G-U-C"
        AnnotationColor.BLACK, AnnotationColor.WHITE -> "a-u-G-U-C"
    }

    fun buildPli(
        uid: String,
        callsign: String,
        lat: Double,
        lon: Double,
        altitude: Double = 9999999.0,
        status: UserStatus? = null,
        staleMs: Long = 120_000L
    ): String {
        val t = System.currentTimeMillis()
        val group = status?.let {
            "<__group name=\"${escape(userStatusToGroupName(it))}\" role=\"Team Member\"/>"
        } ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(uid)}" type="a-f-G-U-C" time="${nowIso(t)}" start="${nowIso(t)}" stale="${nowIso(t + staleMs)}" how="m-g">
  <point lat="$lat" lon="$lon" hae="$altitude" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="${escape(callsign)}"/>
    <takv device="TAK-Lite" platform="TAK-Lite" os="Android" version="1"/>
    $group
  </detail>
</event>"""
    }

    fun buildGeoChat(
        uid: String,
        senderUid: String,
        senderCallsign: String,
        message: String,
        chatroom: String = "All Chat Rooms",
        toUid: String? = null,
        lat: Double = 0.0,
        lon: Double = 0.0
    ): String {
        val t = System.currentTimeMillis()
        val chatId = toUid ?: chatroom
        val dest = toUid ?: chatroom
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(uid)}" type="b-t-f" time="${nowIso(t)}" start="${nowIso(t)}" stale="${nowIso(t + 300_000)}" how="h-g-i-g-o">
  <point lat="$lat" lon="$lon" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <__chat id="${escape(chatId)}" chatroom="${escape(chatroom)}" groupOwner="false" parent="RootContactGroup" senderCallsign="${escape(senderCallsign)}">
      <chatgrp uid0="${escape(senderUid)}" uid1="${escape(dest)}" id="${escape(chatId)}"/>
    </__chat>
    <link uid="${escape(senderUid)}" type="a-f-G-U-C" relation="p-p"/>
    <remarks source="TAK-Lite.${escape(senderUid)}" time="${nowIso(t)}">${escape(message)}</remarks>
  </detail>
</event>"""
    }

    fun buildKeepalive(uid: String): String {
        val t = System.currentTimeMillis()
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(uid)}.ping" type="t-x-d-d" time="${nowIso(t)}" start="${nowIso(t)}" stale="${nowIso(t + 20_000)}" how="m-g">
  <point lat="0" lon="0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail/>
</event>"""
    }

    fun buildAnnotationEvent(
        annotation: MapAnnotation,
        callsign: String?,
        takliteJson: String?,
        includeTaklite: Boolean = true
    ): String {
        val t = annotation.timestamp
        val stale = annotation.expirationTime ?: (t + 24 * 60 * 60 * 1000L)
        val (cotType, lat, lon, label) = when (annotation) {
            is MapAnnotation.PointOfInterest -> Quad(
                annotationColorToCotType(annotation.color),
                annotation.position.lt,
                annotation.position.lng,
                annotation.label ?: annotation.shape.name.lowercase()
            )
            is MapAnnotation.Line -> {
                val first = annotation.points.firstOrNull() ?: LatLngSerializable(0.0, 0.0)
                Quad("u-d-f", first.lt, first.lng, annotation.label ?: "line")
            }
            is MapAnnotation.Area -> Quad(
                "u-d-c-c",
                annotation.center.lt,
                annotation.center.lng,
                annotation.label ?: "area"
            )
            is MapAnnotation.Polygon -> {
                val first = annotation.points.firstOrNull() ?: LatLngSerializable(0.0, 0.0)
                Quad("u-d-f", first.lt, first.lng, annotation.label ?: "polygon")
            }
            is MapAnnotation.Deletion -> Quad("t-x-d-d", 0.0, 0.0, "delete")
        }

        val contact = callsign?.takeIf { it.isNotBlank() }?.let {
            "<contact callsign=\"${escape(it)}\"/>"
        } ?: ""
        val remarks = label?.takeIf { it.isNotBlank() }?.let {
            "<remarks>${escape(it)}</remarks>"
        } ?: ""
        val taklite = if (includeTaklite && !takliteJson.isNullOrBlank()) {
            "<taklite v=\"1\">${escape(takliteJson)}</taklite>"
        } else ""

        // Deletion: ATAK-style tombstone uses same uid with short stale
        if (annotation is MapAnnotation.Deletion) {
            val delT = System.currentTimeMillis()
            return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(annotation.id)}" type="t-x-d-d" time="${nowIso(delT)}" start="${nowIso(delT)}" stale="${nowIso(delT + 5_000)}" how="h-g-i-g-o">
  <point lat="0" lon="0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    $contact
    $taklite
  </detail>
</event>"""
        }

        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(annotation.id)}" type="$cotType" time="${nowIso(t)}" start="${nowIso(t)}" stale="${nowIso(stale)}" how="h-g-i-g-o">
  <point lat="$lat" lon="$lon" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    $contact
    $remarks
    $taklite
  </detail>
</event>"""
    }

    fun buildStatusEvent(
        uid: String,
        callsign: String,
        status: UserStatus,
        takliteJson: String?,
        lat: Double = 0.0,
        lon: Double = 0.0
    ): String {
        val t = System.currentTimeMillis()
        val taklite = takliteJson?.let { "<taklite v=\"1\">${escape(it)}</taklite>" } ?: ""
        return """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="${escape(uid)}" type="a-f-G-U-C" time="${nowIso(t)}" start="${nowIso(t)}" stale="${nowIso(t + 120_000)}" how="m-g">
  <point lat="$lat" lon="$lon" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact callsign="${escape(callsign)}"/>
    <__group name="${escape(userStatusToGroupName(status))}" role="Team Member"/>
    $taklite
  </detail>
</event>"""
    }

    data class ParsedCot(
        val uid: String,
        val type: String,
        val lat: Double,
        val lon: Double,
        val callsign: String?,
        val remarks: String?,
        val groupName: String?,
        val chatroom: String?,
        val senderCallsign: String?,
        val chatMessage: String?,
        val takliteJson: String?,
        val isKeepalive: Boolean,
        val isGeoChat: Boolean,
        val isPli: Boolean,
        val isDelete: Boolean,
        val rawXml: String
    )

    /** Meshtastic node CoT uids are typically `!` + hex node id. */
    fun isMeshtasticNodeUid(uid: String): Boolean =
        MESHTASTIC_NODE_UID.matches(uid)

    /**
     * Unit / PLI track (person on the map), not a drawing or TAK Lite annotation.
     * Meshtastic often emits `a-*` with a non-m-g `how`; node uids (`!hex`) and
     * contact+point still count as tracks. Human-placed markers keep `how=h-g-i-g-o`
     * without a Meshtastic node uid so they remain annotations.
     */
    fun isUnitTrack(
        type: String,
        how: String?,
        uid: String,
        callsign: String?,
        lat: Double,
        lon: Double,
        hasTaklite: Boolean
    ): Boolean {
        if (hasTaklite) return false
        if (type.startsWith("u-d")) return false
        if (!type.startsWith("a-")) return false
        val hasPoint = lat != 0.0 || lon != 0.0
        if (!hasPoint) return false
        val standardHow = how == "m-g" || how == "m-r" || how == "m-g-n"
        if (standardHow) return true
        if (isMeshtasticNodeUid(uid)) return true
        val hasContact = !callsign.isNullOrBlank()
        // Machine/sensor-originated unit with a callsign (Meshtastic may omit m-g)
        if (hasContact && how != "h-g-i-g-o") return true
        return false
    }

    fun parse(xml: String): ParsedCot? {
        val uid = attr(xml, "uid") ?: return null
        val type = attr(xml, "type") ?: return null
        val lat = pointAttr(xml, "lat")?.toDoubleOrNull() ?: 0.0
        val lon = pointAttr(xml, "lon")?.toDoubleOrNull() ?: 0.0
        val callsign = childAttr(xml, "contact", "callsign")
        val remarks = childText(xml, "remarks")
        val groupName = childAttr(xml, "__group", "name")
        val chatroom = childAttr(xml, "__chat", "chatroom")
        val senderCallsign = childAttr(xml, "__chat", "senderCallsign")
        val chatMessage = remarks // GeoChat message lives in remarks
        val taklite = childText(xml, "taklite")
        val how = attr(xml, "how")
        val isKeepalive = type == "t-x-d-d" && uid.endsWith(".ping")
        val isGeoChat = type == "b-t-f"
        val isPli = isUnitTrack(
            type = type,
            how = how,
            uid = uid,
            callsign = callsign,
            lat = lat,
            lon = lon,
            hasTaklite = !taklite.isNullOrBlank()
        )
        val isDelete = type == "t-x-d-d" && !isKeepalive
        return ParsedCot(
            uid = uid,
            type = type,
            lat = lat,
            lon = lon,
            callsign = callsign,
            remarks = remarks,
            groupName = groupName,
            chatroom = chatroom,
            senderCallsign = senderCallsign,
            chatMessage = if (isGeoChat) chatMessage else null,
            takliteJson = taklite,
            isKeepalive = isKeepalive,
            isGeoChat = isGeoChat,
            isPli = isPli,
            isDelete = isDelete,
            rawXml = xml
        )
    }

    /** Best-effort MapAnnotation from standard CoT when &lt;taklite&gt; is absent. */
    fun parsedToAnnotation(parsed: ParsedCot, creatorId: String = "atak"): MapAnnotation? {
        if (parsed.isKeepalive || parsed.isGeoChat || parsed.isPli) return null
        if (parsed.isDelete) {
            return MapAnnotation.Deletion(id = parsed.uid, creatorId = creatorId)
        }
        // ATAK markers often use a-* types with how=h-g-i-g-o (not PLI)
        val color = when {
            parsed.type.startsWith("a-h") || parsed.type.contains("-h-") -> AnnotationColor.RED
            parsed.type.startsWith("a-n") || parsed.type.contains("-n-") -> AnnotationColor.YELLOW
            parsed.type.startsWith("a-f") || parsed.type.contains("-f-") -> AnnotationColor.GREEN
            else -> AnnotationColor.WHITE
        }
        val label = parsed.remarks ?: parsed.callsign
        return when {
            parsed.type.contains("u-d-c") || parsed.type == "u-d-c-c" -> MapAnnotation.Area(
                id = parsed.uid,
                creatorId = creatorId,
                color = color,
                center = LatLngSerializable(parsed.lat, parsed.lon),
                radius = 50.0,
                label = label
            )
            parsed.type.startsWith("u-d") || parsed.type.startsWith("a-") || parsed.type.startsWith("b-m") ->
                MapAnnotation.PointOfInterest(
                    id = parsed.uid,
                    creatorId = creatorId,
                    color = color,
                    position = LatLngSerializable(parsed.lat, parsed.lon),
                    shape = PointShape.CIRCLE,
                    label = label
                )
            else -> MapAnnotation.PointOfInterest(
                id = parsed.uid,
                creatorId = creatorId,
                color = color,
                position = LatLngSerializable(parsed.lat, parsed.lon),
                shape = PointShape.EXCLAMATION,
                label = label
            )
        }
    }

    private fun attr(xml: String, name: String): String? {
        val re = Regex("""\b$name\s*=\s*"([^"]*)"""")
        return re.find(xml)?.groupValues?.getOrNull(1)?.let { unescape(it) }
    }

    private fun pointAttr(xml: String, name: String): String? {
        val point = Regex("""<point\b[^>]*>""", RegexOption.IGNORE_CASE).find(xml)?.value ?: return null
        return attr(point, name)
    }

    private fun childAttr(xml: String, tag: String, name: String): String? {
        val re = Regex("""<$tag\b([^>]*)/?>""", RegexOption.IGNORE_CASE)
        val attrs = re.find(xml)?.groupValues?.getOrNull(1) ?: return null
        return attr("<x $attrs>", name)
    }

    private fun childText(xml: String, tag: String): String? {
        val re = Regex("""<$tag\b[^>]*>(.*?)</$tag>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        return re.find(xml)?.groupValues?.getOrNull(1)?.let { unescape(it.trim()) }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
