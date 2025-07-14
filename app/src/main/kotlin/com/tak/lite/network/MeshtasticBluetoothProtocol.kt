package com.tak.lite.network

import android.content.Context
import android.util.Log
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.MeshProtos.ToRadio
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlin.math.absoluteValue
import kotlin.random.Random

class MeshtasticBluetoothProtocol @Inject constructor(
    private val deviceManager: BluetoothDeviceManager,
    @ApplicationContext private val context: Context,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) : MeshtasticBaseProtocol(context, coroutineContext) {
    private val TAG = "MeshtasticBluetoothProtocol"
    // Official Meshtastic Service UUIDs and Characteristics
    private val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val FROMRADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val TORADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")
    private val FROMNUM_CHARACTERISTIC_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")


    /**
     * Generate a random nonce for handshake security
     * Uses a combination of timestamp and random number to ensure uniqueness
     */
    private fun generateRandomNonce(): Int {
        var random = Random(System.currentTimeMillis()).nextLong().absoluteValue
        val nonceMask = ((1L shl 32) - 1) // A mask for only the valid nonce bits, either 255 or maxint
        random = (random and 0xffffffff) // keep from exceeding 32 bits

        // Use modulus and +1 to ensure we skip 0 on any values we return
        return ((random % nonceMask) + 1L).toInt()
    }

    private var lastDataTime = AtomicLong(System.currentTimeMillis())

    // Add a callback for packet size errors
    var onPacketTooLarge: ((Int, Int) -> Unit)? = null // (actualSize, maxSize)

    private val HANDSHAKE_PACKET_TIMEOUT_MS = 8000L // 8 seconds timeout per packet during handshake


    // Add callback for BLE operation failures
    private var onBleOperationFailed: ((Exception) -> Unit)? = null

    // Add handshake timeout management
    private var handshakeTimeoutJob: Job? = null

    init {
        deviceManager.setPacketListener { data ->
            handleIncomingPacket(data)
        }
        deviceManager.setLostConnectionCallback {
            Log.w(TAG, "Lost connection to device. Will attempt to reconnect.")
        }
        // Listen for initial drain complete to trigger handshake
        deviceManager.setInitialDrainCompleteCallback {
            Log.i(TAG, "Initial FROMRADIO drain complete, starting config handshake.")
            _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
            startConfigHandshake()
        }
        // Set up BLE operation failure callback
        onBleOperationFailed = { exception ->
            Log.w(TAG, "BLE operation failed, notifying packet queue: ${exception.message}")
            // Complete any pending packet responses with failure
            queueResponse.forEach { (_, deferred) ->
                if (!deferred.isCompleted) {
                    deferred.complete(false)
                }
            }
        }
        
        // Connect the BLE operation failure callback
        deviceManager.setBleOperationFailedCallback { exception ->
            onBleOperationFailed?.invoke(exception)
        }
        // Start observing handshake completion
        observeHandshakeCompletion()
        // Observe Bluetooth connection state
        CoroutineScope(coroutineContext).launch {
            deviceManager.connectionState.collect { state ->
                _connectionState.value = when (state) {
                    is BluetoothDeviceManager.ConnectionState.Connected -> {
                        Log.i(TAG, "Connection established - clearing state for fresh start")
                        // Always clear state for fresh connections to ensure clean state
                        cleanupState()
                        resetHandshakeState()
                        MeshConnectionState.Connecting
                    }
                    is BluetoothDeviceManager.ConnectionState.Connecting -> {
                        stopPacketQueue()
                        MeshConnectionState.Connecting
                    }
                    is BluetoothDeviceManager.ConnectionState.Disconnected -> {
                        stopPacketQueue()
                        Log.i(TAG, "Connection lost - clearing state")
                        cleanupState()
                        MeshConnectionState.Disconnected
                    }
                    is BluetoothDeviceManager.ConnectionState.Failed -> {
                        Log.e(TAG, "Connection failed after retry attempts: ${state.reason}")
                        stopPacketQueue()
                        cleanupState()
                        MeshConnectionState.Error(state.reason)
                    }
                }
            }
        }
    }

    override fun cleanupState() {
        Log.d(TAG, "Cleaning up state after disconnection")
        try {
            // Cancel handshake timeout job
            handshakeTimeoutJob?.cancel()
            handshakeTimeoutJob = null

            super.cleanupState()

            // Cleanup device manager last to ensure BLE queue is cleared
            deviceManager.cleanup()
            
            Log.d(TAG, "State cleanup completed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error during state cleanup", e)
            // Even if cleanup fails, ensure we're in a disconnected state
            _connectionState.value = MeshConnectionState.Disconnected
        }
    }

    /**
     * Send a packet to the Bluetooth device. This should only be called by the queue processor.
     * Handles both byte array and MeshPacket inputs.
     * @param data Either a byte array containing a MeshPacket or a MeshPacket directly
     * @return true if the packet was successfully queued, false otherwise
     */
    override fun sendPacket(packet: MeshProtos.MeshPacket): Boolean {
        try {
            val toRadio = ToRadio.newBuilder()
                .setPacket(packet)
                .build()
            val toRadioBytes = toRadio.toByteArray()

            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)

            if (gatt != null && service != null && toRadioChar != null) {
                deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                    result.onSuccess { success ->
                        Log.d(TAG, "Packet id=${packet.id} sent successfully using channel index ${packet.channel}")
                        queueResponse.remove(packet.id)?.complete(success)
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send packet id=${packet.id}", error)
                        queueResponse.remove(packet.id)?.complete(false)
                    }
                }
                return true
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send packet")
                queueResponse.remove(packet.id)?.complete(false)
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet id=${packet.id}", e)
            queueResponse.remove(packet.id)?.complete(false)
            return false
        }
    }

    private fun startConfigHandshake() {
        handshakeComplete.set(false)
        val nonce = generateRandomNonce()
        Log.i(TAG, "Starting Meshtastic config handshake with nonce=$nonce")
        _configDownloadStep.value = ConfigDownloadStep.SendingHandshake
        _configStepCounters.value = emptyMap()  // Reset counters before starting handshake
        val toRadio = com.geeksville.mesh.MeshProtos.ToRadio.newBuilder()
            .setWantConfigId(nonce)
            .build()
        val toRadioBytes = toRadio.toByteArray()
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
            if (gatt != null && service != null && toRadioChar != null) {
                Log.d(TAG, "Sending want_config_id handshake packet")
                deviceManager.reliableWrite(toRadioChar, toRadioBytes) { result ->
                    result.onSuccess { success ->
                        Log.i(TAG, "Sent want_config_id handshake packet: $success")
                        if (success) {
                            _configDownloadStep.value = ConfigDownloadStep.WaitingForConfig
                            // Start handshake drain loop
                            drainFromRadioUntilHandshakeComplete()
                        } else {
                            Log.e(TAG, "Failed to send want_config_id handshake packet")
                            _configDownloadStep.value = ConfigDownloadStep.Error("Failed to send handshake packet")
                            _connectionState.value = MeshConnectionState.Error("Failed to send handshake packet")
                        }
                    }.onFailure { error ->
                        Log.e(TAG, "Failed to send want_config_id: ${error.message}")
                        _configDownloadStep.value = ConfigDownloadStep.Error("Failed to send handshake: ${error.message}")
                        // Set connection state to error since handshake failed
                        _connectionState.value = MeshConnectionState.Error("Failed to send handshake: ${error.message}")
                    }
                }
            } else {
                Log.e(TAG, "GATT/service/ToRadio characteristic missing, cannot send handshake")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/ToRadio characteristic missing")
                _connectionState.value = MeshConnectionState.Error("GATT/service/ToRadio characteristic missing")
            }
        }
    }

    private fun drainFromRadioUntilHandshakeComplete() {
        CoroutineScope(coroutineContext).launch {
            val gatt = deviceManager.connectedGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
            
            if (gatt != null && fromRadioChar != null) {
                try {
                    // Reset last data time when starting handshake
                    lastDataTime.set(System.currentTimeMillis())
                    
                    // Start the initial handshake timeout
                    startHandshakeTimeout()
                    
                    while (!handshakeComplete.get()) {
                        deviceManager.aggressiveDrainFromRadio(gatt, fromRadioChar)
                        
                        // Wait a short time before next drain attempt to avoid tight loop
                        delay(100)
                    }
                    
                    if (handshakeComplete.get()) {
                        Log.i(TAG, "Handshake complete.")
                        _configDownloadStep.value = ConfigDownloadStep.Complete
                        // Cancel the timeout job since handshake is complete
                        handshakeTimeoutJob?.cancel()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during handshake drain: ${e.message}", e)
                    _configDownloadStep.value = ConfigDownloadStep.Error("Handshake drain error: ${e.message}")
                    cleanupState()
                }
            } else {
                Log.e(TAG, "GATT/service/FROMRADIO characteristic missing, cannot drain during handshake.")
                _configDownloadStep.value = ConfigDownloadStep.Error("GATT/service/FROMRADIO characteristic missing")
                // Set connection state to error since handshake failed
                _connectionState.value = MeshConnectionState.Error("GATT/service/FROMRADIO characteristic missing")
                cleanupState()
            }
        }
    }

    /**
     * Reset handshake state for fresh connections
     * This should be called when establishing a new connection after a complete disconnect
     */
    private fun resetHandshakeState() {
        Log.d(TAG, "Resetting handshake state for fresh connection")
        handshakeComplete.set(false)
        _configDownloadStep.value = ConfigDownloadStep.NotStarted
        _configStepCounters.value = emptyMap()
        handshakeTimeoutJob?.cancel()
        handshakeTimeoutJob = null
    }

    /**
     * Start or restart the handshake timeout job
     * This timeout gets reset every time a packet is received during the handshake process,
     * ensuring that the handshake doesn't timeout as long as the device is actively sending data.
     */
    private fun startHandshakeTimeout() {
        // Cancel any existing timeout job
        handshakeTimeoutJob?.cancel()
        
        handshakeTimeoutJob = CoroutineScope(coroutineContext).launch {
            try {
                delay(HANDSHAKE_PACKET_TIMEOUT_MS)
                // Check if we've received any data recently
                val timeSinceLastData = System.currentTimeMillis() - lastDataTime.get()
                if (timeSinceLastData > HANDSHAKE_PACKET_TIMEOUT_MS) {
                    Log.w(TAG, "No data received for ${HANDSHAKE_PACKET_TIMEOUT_MS/1000}s during handshake (last data: ${timeSinceLastData}ms ago)")
                    _configDownloadStep.value = ConfigDownloadStep.Error("No response from radio")
                    // Set connection state to error since handshake failed
                    _connectionState.value = MeshConnectionState.Error("No response from radio")
                    // For handshake timeout, we should do a full cleanup since the device is not responding
                    cleanupState()
                } else {
                    // If we received data recently, restart the timeout
                    Log.d(TAG, "Restarting handshake timeout after receiving data (last data: ${timeSinceLastData}ms ago)")
                    startHandshakeTimeout()
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // This is expected when handshake completes successfully and timeout is cancelled
                Log.d(TAG, "Handshake timeout job cancelled (expected when handshake completes)")
            } catch (e: Exception) {
                Log.e(TAG, "Error in handshake timeout job", e)
            }
        }
    }

    // Call this when you receive a packet from the device
    private fun handleIncomingPacket(data: ByteArray) {
        // Update last data time for handshake timeout
        lastDataTime.set(System.currentTimeMillis())
        
        // Reset handshake timeout if we're still in handshake process
        // This ensures the handshake doesn't timeout as long as we're receiving packets
        if (!handshakeComplete.get()) {
            Log.d(TAG, "Received packet during handshake - resetting timeout")
            startHandshakeTimeout()
        }
        
        Log.d(TAG, "handleIncomingPacket called with data: "+
            "${data.size} bytes: ${data.joinToString(", ", limit = 16)}")
        if (data.size > 252) {
            Log.e(TAG, "handleIncomingPacket: Received packet size ${data.size} exceeds safe MTU payload (252 bytes)")
        }
        try {
            val fromRadio = com.geeksville.mesh.MeshProtos.FromRadio.parseFrom(data)
            Log.d(TAG, "Parsed FromRadio: $fromRadio")
            when (fromRadio.payloadVariantCase) {
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG -> {
                    Log.i(TAG, "Received CONFIG during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingConfig
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingConfig)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MODULECONFIG -> {
                    Log.i(TAG, "Received MODULECONFIG during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingModuleConfig
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingModuleConfig)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CHANNEL -> {
                    Log.i(TAG, "Received CHANNEL update.")
                    if (!handshakeComplete.get()) {
                        _configDownloadStep.value = ConfigDownloadStep.DownloadingChannel
                        updateConfigStepCounter(ConfigDownloadStep.DownloadingChannel)
                    }
                    // Handle the channel update regardless of handshake state
                    handleChannelUpdate(fromRadio.channel)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.NODE_INFO -> {
                    Log.i(TAG, "Received NODE_INFO during handshake.")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingNodeInfo
                    updateConfigStepCounter(ConfigDownloadStep.DownloadingNodeInfo)
                    handleNodeInfo(fromRadio.nodeInfo)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.MY_INFO -> {
                    handleMyInfo(fromRadio.myInfo)
                    Log.d(TAG, "Received MyNodeInfo, nodeId: $connectedNodeId")
                    _configDownloadStep.value = ConfigDownloadStep.DownloadingMyInfo
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.CONFIG_COMPLETE_ID -> {
                    val completeId = fromRadio.configCompleteId
                    Log.i(TAG, "Received CONFIG_COMPLETE_ID: $completeId")
                    
                    // Complete the handshake regardless of ID match (device might send stale ID first)
                    handshakeComplete.set(true)
                    Log.i(TAG, "Meshtastic config handshake complete!")
                    _configDownloadStep.value = ConfigDownloadStep.Complete
                    
                    // Set connection state to Connected now that handshake is complete
                    val currentDevice = deviceManager.connectedGatt?.device
                    if (currentDevice != null) {
                        _connectionState.value = MeshConnectionState.Connected(com.tak.lite.di.DeviceInfo.BluetoothDevice(currentDevice))
                        Log.i(TAG, "Connection state set to Connected for device: ${currentDevice.name}")
                    } else {
                        Log.w(TAG, "No connected device found when setting connection state to Connected")
                    }
                    
                    // Subscribe to FROMNUM notifications only after handshake is complete
                    deviceManager.subscribeToFromNumNotifications()
                    
                    // Restore selected channel from preferences after handshake is complete
                    val prefs = context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE)
                    val savedChannelId = prefs.getString("selected_channel_id", null)
                    if (savedChannelId != null) {
                        Log.d(TAG, "Restoring saved channel selection: $savedChannelId")
                        CoroutineScope(coroutineContext).launch {
                            selectChannel(savedChannelId)
                        }
                    }

                    // Start the packet queue now that handshake is complete
                    startPacketQueue()
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.PACKET -> {
                    if (!handshakeComplete.get()) {
                        Log.w(TAG, "Ignoring mesh packet before handshake complete.")
                        return
                    }
                    handlePacket(fromRadio.packet, fromRadio.payloadVariantCase)
                }
                com.geeksville.mesh.MeshProtos.FromRadio.PayloadVariantCase.QUEUESTATUS -> {
                    handleQueueStatus(fromRadio.queueStatus)
                }
                else -> {
                    Log.d(TAG, "Ignored packet with payloadVariantCase: ${fromRadio.payloadVariantCase}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing incoming packet: ${e.message}", e)
        }
    }

    // Add handshake completion observer
    private fun observeHandshakeCompletion() {
        CoroutineScope(coroutineContext).launch {
            _configDownloadStep.collect { step ->
                if (step == ConfigDownloadStep.Complete) {
                    Log.i(TAG, "Handshake complete")
                    // No need to recover in-flight messages since we clear state on every connection
                }
            }
        }
    }

    private fun updateConfigStepCounter(step: ConfigDownloadStep) {
        val currentCounters = _configStepCounters.value.toMutableMap()
        currentCounters[step] = (currentCounters[step] ?: 0) + 1
        _configStepCounters.value = currentCounters
    }

    // Device management implementation
    override fun scanForDevices(onResult: (com.tak.lite.di.DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        deviceManager.scanForDevices(MESHTASTIC_SERVICE_UUID, 
            onResult = { bluetoothDevice ->
                // Check if this device is already connected at OS level
                val isConnected = deviceManager.isDeviceConnectedAtOsLevel(bluetoothDevice)
                Log.i(TAG, "Found device: ${bluetoothDevice.name ?: "Unknown"} (${bluetoothDevice.address}) - Connected at OS level: $isConnected")
                onResult(com.tak.lite.di.DeviceInfo.BluetoothDevice(bluetoothDevice))
            },
            onScanFinished = onScanFinished
        )
    }

    override fun connectToDevice(deviceInfo: com.tak.lite.di.DeviceInfo, onConnected: (Boolean) -> Unit) {
        when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                deviceManager.connect(deviceInfo.device, onConnected)
            }
            is com.tak.lite.di.DeviceInfo.AidlDevice -> {
                // Bluetooth protocol doesn't support AIDL devices
                onConnected(false)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> {
                // Network devices are not supported by Meshtastic protocol
                onConnected(false)
            }
        }
    }

    override fun disconnectFromDevice() {
        deviceManager.disconnect()
    }

    /**
     * Force a complete reset of the Bluetooth state
     * This should be called when the user wants to start completely fresh after connection issues
     */
    override fun forceReset() {
        Log.i(TAG, "Force reset requested from protocol layer")
        deviceManager.forceReset()
        cleanupState()
    }



    /**
     * Check if the system is ready for new connections
     * @return true if ready for new connections, false otherwise
     */
    override fun isReadyForNewConnection(): Boolean {
        return deviceManager.isReadyForNewConnection()
    }

    /**
     * Get diagnostic information about the current connection state
     * @return String with diagnostic information
     */
    override fun getDiagnosticInfo(): String {
        return "Bluetooth Protocol State: ${_connectionState.value}, " +
               "Connected Device: ${deviceManager.connectedGatt?.device?.name ?: "None"}, " +
               "Handshake Complete: $handshakeComplete, " +
               "Config Step: ${_configDownloadStep.value}, " +
               "My ID: ${_localNodeIdOrNickname.value}"
    }
    
    override fun getLocalUserInfo(): Pair<String, String>? {
        return super.getLocalUserInfoInternal()
    }

    /**
     * Nuclear option: Force a complete Bluetooth adapter reset
     * This should only be used when the OS Bluetooth stack is completely stuck
     * WARNING: This will disconnect ALL Bluetooth devices and restart the Bluetooth stack
     */
    fun forceBluetoothAdapterReset() {
        Log.w(TAG, "NUCLEAR OPTION: Force Bluetooth adapter reset requested")
        deviceManager.forceBluetoothAdapterReset()

        // Also cleanup protocol state
        cleanupState()
    }

    /**
     * Get detailed OS-level Bluetooth connection information for debugging
     * @return String with detailed OS connection information
     */
    fun getOsConnectionInfo(): String {
        return deviceManager.getOsConnectionInfo()
    }



    /**
     * Get a list of all connected devices that support the Meshtastic service
     * This can be useful for debugging and manual connection scenarios
     * @return List of connected devices that support the Meshtastic service
     */
    fun getConnectedMeshtasticDevices(): List<com.tak.lite.di.DeviceInfo> {
        val connectedDevices = deviceManager.getConnectedMeshtasticDevices(MESHTASTIC_SERVICE_UUID)
        return connectedDevices.map { device ->
            com.tak.lite.di.DeviceInfo.BluetoothDevice(device)
        }
    }

    /**
     * Check if a specific device is connected at the OS level
     * @param deviceInfo The device to check
     * @return true if the device is connected at OS level, false otherwise
     */
    fun isDeviceConnectedAtOsLevel(deviceInfo: com.tak.lite.di.DeviceInfo): Boolean {
        return when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                deviceManager.isDeviceConnectedAtOsLevel(deviceInfo.device)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> false
            is com.tak.lite.di.DeviceInfo.AidlDevice -> false
        }
    }
} 