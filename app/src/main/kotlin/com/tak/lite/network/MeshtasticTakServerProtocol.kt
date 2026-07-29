package com.tak.lite.network

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.DirectMessageChannel
import com.tak.lite.data.model.IChannel
import com.tak.lite.data.model.MessageStatus
import com.tak.lite.data.model.MeshtasticChannel
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.DeviceInfo
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.model.AnnotationStatus
import com.tak.lite.model.DataSource
import com.tak.lite.model.MapAnnotation
import com.tak.lite.model.PacketSummary
import com.tak.lite.model.PeerLocationEntry
import com.tak.lite.model.UserStatus
import com.tak.lite.model.copyAsMesh
import com.tak.lite.network.takserver.CotXml
import com.tak.lite.network.takserver.TakDataPackage
import com.tak.lite.network.takserver.TakLiteDetail
import com.tak.lite.network.takserver.TakServerTlsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * MeshProtocol over Meshtastic Local TAK Server (mTLS CoT on :8089).
 * Replaces the removed AIDL integration.
 */
class MeshtasticTakServerProtocol(
    private val context: Context
) : MeshProtocol {
    private val TAG = "MeshtasticTakServer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var keepaliveJob: Job? = null
    private var client: TakServerTlsClient? = null

    private val _connectionState = MutableStateFlow<MeshConnectionState>(MeshConnectionState.Disconnected)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    private val meshChannel = MeshtasticChannel(
        id = "0_Mesh",
        name = "Mesh",
        displayName = "Mesh",
        isDefault = true,
        role = MeshtasticChannel.ChannelRole.PRIMARY,
        index = 0
    )
    private val _channels = MutableStateFlow<List<IChannel>>(listOf(meshChannel))
    override val channels: StateFlow<List<IChannel>> = _channels.asStateFlow()

    private val _channelMessages = MutableStateFlow<Map<String, List<ChannelMessage>>>(emptyMap())
    override val channelMessages: StateFlow<Map<String, List<ChannelMessage>>> = _channelMessages.asStateFlow()

    private val _peers = MutableStateFlow<List<MeshPeer>>(emptyList())
    override val peers: StateFlow<List<MeshPeer>> = _peers.asStateFlow()

    private val _localNodeIdOrNickname = MutableStateFlow<String?>(null)
    override val localNodeIdOrNickname: StateFlow<String?> = _localNodeIdOrNickname.asStateFlow()

    private val _packetSummaries = MutableStateFlow<List<PacketSummary>>(emptyList())
    override val packetSummaries: StateFlow<List<PacketSummary>> = _packetSummaries.asStateFlow()

    private val _configStepCounters = MutableStateFlow<Map<ConfigDownloadStep, Int>>(emptyMap())
    override val configStepCounters: StateFlow<Map<ConfigDownloadStep, Int>> = _configStepCounters.asStateFlow()

    private val _annotationStatusUpdates = MutableStateFlow<Map<String, AnnotationStatus>>(emptyMap())
    override val annotationStatusUpdates: StateFlow<Map<String, AnnotationStatus>> = _annotationStatusUpdates.asStateFlow()

    override val requiresAppLocationSend: Boolean = true
    override val allowsChannelManagement: Boolean = false
    override val supportsAudio: Boolean = false
    override val requiresConnection: Boolean = true

    private var annotationCallback: ((MapAnnotation) -> Unit)? = null
    private var peerLocationCallback: ((Map<String, PeerLocationEntry>) -> Unit)? = null
    private var userLocationCallback: ((LatLng) -> Unit)? = null
    private var localNickname: String = "TAK-Lite"
    private var localUid: String = "TAKLITE-" + UUID.randomUUID().toString().take(8)
    /** CoT uid of the Meshtastic radio (often `!hex`), learned/persisted so radio PLI is "you". */
    private var meshtasticSelfUid: String? = null
    private var lastLat: Double = 0.0
    private var lastLon: Double = 0.0
    private var lastStatus: UserStatus? = null
    private val peerLocations = ConcurrentHashMap<String, PeerLocationEntry>()
    private val peerNames = ConcurrentHashMap<String, String>()
    private val requestIdGen = AtomicInteger(1)
    private val dmChannels = ConcurrentHashMap<String, DirectMessageChannel>()

    init {
        val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        prefs.getString("nickname", null)?.let {
            localNickname = it
            _localNodeIdOrNickname.value = it
        }
        prefs.getString("tak_server_uid", null)?.let { localUid = it }
            ?: prefs.edit().putString("tak_server_uid", localUid).apply()
        meshtasticSelfUid = prefs.getString(PREF_MESH_SELF_UID, null)
    }

    private fun prefs() = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    private fun isSelfCotUid(uid: String): Boolean {
        if (uid == localUid || uid.startsWith("$localUid.")) return true
        val self = meshtasticSelfUid ?: return false
        return uid == self || uid.startsWith("$self.")
    }

    private fun rememberMeshtasticSelfUid(uid: String, reason: String) {
        if (uid.isBlank() || uid == localUid || uid.startsWith("TAKLITE-")) return
        if (meshtasticSelfUid == uid) return
        Log.i(TAG, "Learning Meshtastic radio self uid=$uid ($reason)")
        meshtasticSelfUid = uid
        prefs().edit().putString(PREF_MESH_SELF_UID, uid).apply()
        // If we previously treated this uid as a peer/POI, drop it from the peer list
        if (peerLocations.remove(uid) != null || peerNames.remove(uid) != null) {
            _peers.value = peerLocations.keys.map { id ->
                MeshPeer(
                    id = id,
                    ipAddress = id,
                    lastSeen = peerLocations[id]?.timestamp ?: 0L,
                    nickname = peerNames[id],
                    longName = peerNames[id]
                )
            }
            peerLocationCallback?.invoke(HashMap(peerLocations))
        }
    }

    private fun maybeLearnSelf(parsed: CotXml.ParsedCot) {
        if (meshtasticSelfUid != null) return
        if (!parsed.isPli) return
        val callsign = parsed.callsign ?: return
        if (callsign.equals(localNickname, ignoreCase = true)) {
            rememberMeshtasticSelfUid(parsed.uid, "callsign matches nickname")
        }
    }

    private fun applyOwnLocation(lat: Double, lon: Double, status: UserStatus?) {
        if (lat == 0.0 && lon == 0.0) return
        lastLat = lat
        lastLon = lon
        status?.let { lastStatus = it }
        userLocationCallback?.invoke(LatLng(lat, lon))
    }

    fun isServiceResponsive(): Boolean = client?.isConnected() == true

    fun isMeshtasticInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo("com.geeksville.mesh", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    override fun scanForDevices(onResult: (DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        if (TakDataPackage.hasImportedPackage(context) || isMeshtasticInstalled()) {
            onResult(DeviceInfo.TakServerDevice())
        }
        onScanFinished()
    }

    override fun connectToDevice(deviceInfo: DeviceInfo, onConnected: (Boolean) -> Unit) {
        if (deviceInfo !is DeviceInfo.TakServerDevice) {
            onConnected(false)
            return
        }
        val config = TakDataPackage.loadConfig(context)
        if (config == null || !config.isReady()) {
            _connectionState.value = MeshConnectionState.Error(
                "Import the Meshtastic TAK data package first (Settings → Import data package)"
            )
            onConnected(false)
            return
        }
        if (!isMeshtasticInstalled()) {
            _connectionState.value = MeshConnectionState.Error("Meshtastic app is not installed")
            onConnected(false)
            return
        }

        _connectionState.value = MeshConnectionState.Connecting
        client?.disconnect()
        client = TakServerTlsClient(
            config = config,
            onEvent = { xml -> handleCot(xml) },
            onConnectionChanged = { connected, error ->
                // Callbacks originate on TakServerConnect/reader threads — hop to Main for UI/state observers.
                scope.launch(Dispatchers.Main) {
                    if (connected) {
                        _connectionState.value = MeshConnectionState.Connected(DeviceInfo.TakServerDevice())
                        startKeepalive()
                        onConnected(true)
                    } else {
                        stopKeepalive()
                        if (error != null) {
                            _connectionState.value = MeshConnectionState.Error(error)
                        } else {
                            _connectionState.value = MeshConnectionState.Disconnected
                        }
                        onConnected(false)
                    }
                }
            }
        )
        client?.connect()
    }

    override fun disconnectFromDevice() {
        stopKeepalive()
        client?.disconnect()
        client = null
        _connectionState.value = MeshConnectionState.Disconnected
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = scope.launch {
            while (isActive) {
                client?.send(CotXml.buildKeepalive(localUid))
                delay(10_000)
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    private fun handleCot(xml: String) {
        val parsed = CotXml.parse(xml) ?: return
        if (parsed.isKeepalive) return
        if (parsed.uid == localUid || parsed.uid.startsWith("$localUid.")) return

        maybeLearnSelf(parsed)

        // Meshtastic radio (or other self aliases) → own location, never peer/POI
        if (isSelfCotUid(parsed.uid)) {
            when {
                parsed.isGeoChat -> { /* ignore echo of our chat */ }
                parsed.takliteJson != null -> {
                    TakLiteDetail.statusFromJson(parsed.takliteJson)?.let { status ->
                        applyOwnLocation(parsed.lat, parsed.lon, status)
                    }
                }
                parsed.isPli || (parsed.lat != 0.0 || parsed.lon != 0.0) -> {
                    val status = CotXml.groupNameToUserStatus(parsed.groupName)
                    applyOwnLocation(parsed.lat, parsed.lon, status)
                }
            }
            return
        }

        when {
            parsed.isGeoChat -> {
                val content = parsed.chatMessage ?: parsed.remarks ?: return
                val sender = parsed.senderCallsign ?: parsed.callsign ?: parsed.uid
                val channelId = if (parsed.chatroom != null && parsed.chatroom != "All Chat Rooms") {
                    // treat as DM if chatroom looks like a peer uid
                    val peerId = parsed.chatroom
                    getOrCreateDirectMessageChannel(peerId)
                    DirectMessageChannel.createId(peerId)
                } else {
                    meshChannel.id
                }
                appendMessage(
                    ChannelMessage(
                        senderId = parsed.uid,
                        senderShortName = sender,
                        content = content,
                        requestId = requestIdGen.getAndIncrement(),
                        status = MessageStatus.RECEIVED,
                        channelId = channelId
                    )
                )
            }
            parsed.takliteJson != null -> {
                // Status update?
                TakLiteDetail.statusFromJson(parsed.takliteJson)?.let { status ->
                    updatePeerLocation(
                        parsed.uid,
                        parsed.lat,
                        parsed.lon,
                        parsed.callsign ?: parsed.senderCallsign,
                        status
                    )
                    return
                }
                TakLiteDetail.bulkDeleteFromJson(parsed.takliteJson)?.let { ids ->
                    ids.forEach { id ->
                        annotationCallback?.invoke(
                            MapAnnotation.Deletion(id = id, creatorId = parsed.uid).copyAsMesh()
                        )
                    }
                    return
                }
                TakLiteDetail.annotationFromJson(parsed.takliteJson)?.let { ann ->
                    annotationCallback?.invoke(ann.copyAsMesh())
                    return
                }
            }
            parsed.isDelete -> {
                annotationCallback?.invoke(
                    MapAnnotation.Deletion(id = parsed.uid, creatorId = parsed.uid).copyAsMesh()
                )
            }
            parsed.isPli -> {
                // First Meshtastic-style unit after connect with no learned self yet:
                // Meshtastic typically broadcasts the local radio immediately.
                if (meshtasticSelfUid == null && CotXml.isMeshtasticNodeUid(parsed.uid) &&
                    peerLocations.isEmpty()
                ) {
                    rememberMeshtasticSelfUid(parsed.uid, "first mesh node PLI after connect")
                    val status = CotXml.groupNameToUserStatus(parsed.groupName)
                    applyOwnLocation(parsed.lat, parsed.lon, status)
                    return
                }
                val status = CotXml.groupNameToUserStatus(parsed.groupName) ?: lastStatus
                updatePeerLocation(
                    parsed.uid,
                    parsed.lat,
                    parsed.lon,
                    parsed.callsign,
                    status
                )
            }
            else -> {
                CotXml.parsedToAnnotation(parsed)?.let {
                    annotationCallback?.invoke(it.copyAsMesh())
                }
            }
        }
    }

    private fun updatePeerLocation(
        uid: String,
        lat: Double,
        lon: Double,
        name: String?,
        status: UserStatus?
    ) {
        if (lat == 0.0 && lon == 0.0) return
        name?.let { peerNames[uid] = it }
        val entry = PeerLocationEntry(
            timestamp = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            userStatus = status
        )
        peerLocations[uid] = entry
        _peers.value = peerLocations.keys.map { id ->
            MeshPeer(
                id = id,
                ipAddress = id,
                lastSeen = peerLocations[id]?.timestamp ?: 0L,
                nickname = peerNames[id],
                longName = peerNames[id]
            )
        }
        peerLocationCallback?.invoke(HashMap(peerLocations))
    }

    private fun appendMessage(msg: ChannelMessage) {
        val map = _channelMessages.value.toMutableMap()
        val list = map[msg.channelId]?.toMutableList() ?: mutableListOf()
        list.add(msg)
        map[msg.channelId] = list
        _channelMessages.value = map
    }

    private fun markAnnotation(id: String, status: AnnotationStatus) {
        val map = _annotationStatusUpdates.value.toMutableMap()
        map[id] = status
        _annotationStatusUpdates.value = map
    }

    override fun createChannel(name: String) {}
    override fun deleteChannel(channelId: String) {}
    override fun selectChannel(channelId: String) {}

    override fun sendAnnotation(annotation: MapAnnotation) {
        val json = TakLiteDetail.annotationJson(annotation)
        val xml = CotXml.buildAnnotationEvent(annotation, localNickname, json, includeTaklite = true)
        val ok = client?.send(xml) == true
        markAnnotation(annotation.id, if (ok) AnnotationStatus.SENT else AnnotationStatus.FAILED)
    }

    override fun sendBulkAnnotationDeletions(ids: List<String>) {
        ids.forEach { id ->
            sendAnnotation(
                MapAnnotation.Deletion(id = id, creatorId = localUid, source = DataSource.LOCAL)
            )
        }
        // Also send compact bulk payload for TAK Lite peers
        val bulkJson = TakLiteDetail.bulkDeleteJson(ids)
        val stub = MapAnnotation.Deletion(id = "bulk-${UUID.randomUUID()}", creatorId = localUid)
        val xml = CotXml.buildAnnotationEvent(stub, localNickname, bulkJson, includeTaklite = true)
        client?.send(xml)
    }

    override fun sendLocationUpdate(latitude: Double, longitude: Double) {
        lastLat = latitude
        lastLon = longitude
        // Do not invoke userLocationCallback here — phone GPS is already applied via
        // setPhoneLocation / MapLibre location component. Callback is reserved for
        // Meshtastic radio self-PLI (applyOwnLocation) so we don't flood map recenter.
        val xml = CotXml.buildPli(
            uid = localUid,
            callsign = localNickname,
            lat = latitude,
            lon = longitude,
            status = lastStatus
        )
        client?.send(xml)
    }

    override fun sendStatusUpdate(status: UserStatus) {
        lastStatus = status
        val json = TakLiteDetail.statusJson(status)
        val xml = CotXml.buildStatusEvent(
            uid = localUid,
            callsign = localNickname,
            status = status,
            takliteJson = json,
            lat = lastLat,
            lon = lastLon
        )
        client?.send(xml)
    }

    override fun syncAmbientLedWithStatus(status: UserStatus) {
        // Not available via Local TAK Server
        Log.d(TAG, "Ambient LED sync unsupported in TAK Server mode")
    }

    override fun sendTextMessage(channelId: String, content: String) {
        val uid = "GeoChat.$localUid.${UUID.randomUUID()}"
        val xml = CotXml.buildGeoChat(
            uid = uid,
            senderUid = localUid,
            senderCallsign = localNickname,
            message = content,
            chatroom = "All Chat Rooms",
            lat = lastLat,
            lon = lastLon
        )
        val ok = client?.send(xml) == true
        appendMessage(
            ChannelMessage(
                senderId = localUid,
                senderShortName = localNickname,
                content = content,
                requestId = requestIdGen.getAndIncrement(),
                status = if (ok) MessageStatus.SENT else MessageStatus.ERROR,
                channelId = meshChannel.id
            )
        )
    }

    override fun sendDirectMessage(peerId: String, content: String) {
        val uid = "GeoChat.$localUid.${UUID.randomUUID()}"
        val peerName = peerNames[peerId] ?: peerId
        val xml = CotXml.buildGeoChat(
            uid = uid,
            senderUid = localUid,
            senderCallsign = localNickname,
            message = content,
            chatroom = peerId,
            toUid = peerId,
            lat = lastLat,
            lon = lastLon
        )
        val ok = client?.send(xml) == true
        val channelId = DirectMessageChannel.createId(peerId)
        getOrCreateDirectMessageChannel(peerId)
        appendMessage(
            ChannelMessage(
                senderId = localUid,
                senderShortName = localNickname,
                content = content,
                requestId = requestIdGen.getAndIncrement(),
                status = if (ok) MessageStatus.SENT else MessageStatus.ERROR,
                channelId = channelId
            )
        )
        Log.d(TAG, "DM to $peerName ok=$ok")
    }

    override fun setAnnotationCallback(callback: (MapAnnotation) -> Unit) {
        annotationCallback = callback
    }

    override fun setPeerLocationCallback(callback: (Map<String, PeerLocationEntry>) -> Unit) {
        peerLocationCallback = callback
    }

    override fun setUserLocationCallback(callback: (LatLng) -> Unit) {
        userLocationCallback = callback
    }

    override fun sendAudioData(audioData: ByteArray, channelId: String) {}

    override fun setLocalNickname(nickname: String) {
        localNickname = nickname
        _localNodeIdOrNickname.value = nickname
        prefs().edit().putString("nickname", nickname).apply()
        // If a known peer already has this callsign, promote them to self (radio identity)
        if (meshtasticSelfUid == null && nickname.isNotBlank()) {
            peerNames.entries.firstOrNull { it.value.equals(nickname, ignoreCase = true) }?.key?.let { uid ->
                rememberMeshtasticSelfUid(uid, "nickname aligned with peer callsign")
                peerLocations[uid]?.let { entry ->
                    applyOwnLocation(entry.latitude, entry.longitude, entry.userStatus)
                }
            }
        }
    }

    override fun sendStateSync(
        toIp: String,
        channels: List<IChannel>,
        peerLocations: Map<String, PeerLocationEntry>,
        annotations: List<MapAnnotation>,
        partialUpdate: Boolean,
        updateFields: Set<String>
    ) {
        // Not applicable for TAK Server mode
    }

    override fun getChannelName(channelId: String): String? =
        _channels.value.find { it.id == channelId }?.displayName
            ?: dmChannels[channelId]?.displayName

    override fun requestPeerLocation(peerId: String, onPeerLocationReceived: (timeout: Boolean) -> Unit) {
        // No RPC over CoT; succeed if we already have a recent fix
        val has = peerLocations.containsKey(peerId)
        onPeerLocationReceived(!has)
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? = null

    override fun getPeerName(peerId: String): String? = peerNames[peerId]

    override fun getPeerLastHeard(peerId: String): Long? = peerLocations[peerId]?.timestamp

    override fun getOrCreateDirectMessageChannel(peerId: String): DirectMessageChannel? {
        val id = DirectMessageChannel.createId(peerId)
        return dmChannels.getOrPut(id) {
            DirectMessageChannel(
                id = id,
                name = peerNames[peerId] ?: peerId,
                displayName = peerNames[peerId] ?: peerId,
                peerId = peerId
            )
        }
    }

    override fun forceReset() {
        disconnectFromDevice()
        val config = TakDataPackage.loadConfig(context)
        if (config != null) {
            connectToDevice(DeviceInfo.TakServerDevice()) { }
        }
    }

    override fun cleanupState() {
        disconnectFromDevice()
        peerLocations.clear()
        peerNames.clear()
        _peers.value = emptyList()
        _channelMessages.value = emptyMap()
    }

    override fun isReadyForNewConnection(): Boolean =
        _connectionState.value is MeshConnectionState.Disconnected ||
            _connectionState.value is MeshConnectionState.Error

    override fun getDiagnosticInfo(): String {
        val cfg = TakDataPackage.loadConfig(context)
        return "TakServer connected=${client?.isConnected()} host=${cfg?.host}:${cfg?.port} " +
            "peers=${_peers.value.size} selfUid=$localUid radioUid=$meshtasticSelfUid"
    }

    override fun getLocalUserInfo(): Pair<String, String>? = localUid to localNickname

    companion object {
        private const val PREF_MESH_SELF_UID = "meshtastic_tak_self_uid"
    }
}
