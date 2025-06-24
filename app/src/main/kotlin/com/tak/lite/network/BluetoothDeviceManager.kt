package com.tak.lite.network

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.Job

fun longBLEUUID(hexFour: String): UUID = UUID.fromString("0000$hexFour-0000-1000-8000-00805f9b34fb")

class BluetoothDeviceManager(private val context: Context) {
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val device: BluetoothDevice) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

    private val configurationDescriptorUUID =
        longBLEUUID("2902")

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private var bluetoothGatt: BluetoothGatt? = null
    val connectedGatt: BluetoothGatt?
        get() = bluetoothGatt

    private var gattCallback: BluetoothGattCallback? = null

    // Packet listener for incoming mesh packets
    private var packetListener: ((ByteArray) -> Unit)? = null
    fun setPacketListener(listener: (ByteArray) -> Unit) {
        packetListener = listener
    }

    // Meshtastic FROMRADIO characteristic UUID
    private val FROMRADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("2c55e69e-4993-11ed-b878-0242ac120002")
    private val MESHTASTIC_SERVICE_UUID: UUID = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")
    private val TORADIO_CHARACTERISTIC_UUID: UUID = UUID.fromString("f75c76d2-129e-4dad-a1dd-7866124401e7")

    // Add FROMNUM characteristic UUID
    private val FROMNUM_CHARACTERISTIC_UUID: UUID = UUID.fromString("ed9da18c-a800-4f66-a670-aa7547e34453")

    private val MAX_RECONNECT_ATTEMPTS = 5
    private val MAX_RECONNECT_TIME_MS = 30000L // 30 seconds total time for reconnection attempts
    private var reconnectStartTime: Long = 0
    private var reconnectAttempts = 0
    private var pendingReconnect: Boolean = false
    private var lastDevice: BluetoothDevice? = null
    private var lastOnConnected: ((Boolean) -> Unit)? = null
    private var mtuRetry = false
    private var skipRefresh: Boolean = false
    private var initialDrainDone = false
    private var isReconnectionAttempt: Boolean = false // Track if this is a reconnection attempt

    // BLE Work Queue
    private sealed class BleOperation {
        data class Write(val data: ByteArray, val onResult: (Result<Boolean>) -> Unit) : BleOperation()
        data class Read(val characteristic: BluetoothGattCharacteristic, val onResult: (Result<ByteArray?>) -> Unit, val attempt: Int = 1) : BleOperation()
        data class SetNotify(val characteristic: BluetoothGattCharacteristic, val enable: Boolean, val onResult: (Result<Boolean>) -> Unit) : BleOperation()
        data class ReliableWrite(val characteristic: BluetoothGattCharacteristic, val value: ByteArray, val onResult: (Result<Boolean>) -> Unit, val attempt: Int = 1) : BleOperation()
    }
    private val bleQueue: Queue<BleOperation> = ConcurrentLinkedQueue()
    @Volatile private var bleOperationInProgress = false
    private var currentOperationTimeoutJob: Job? = null
    private var currentOperation: BleOperation? = null
    private val OPERATION_TIMEOUT_MS = 4000L // 2 seconds per operation
    private val MAX_READ_ATTEMPTS = 3 // Maximum read attempts before escalation

    private var pendingWriteCallback: ((Result<Boolean>) -> Unit)? = null
    private var pendingReadCallback: ((Result<ByteArray?>) -> Unit)? = null
    private var pendingSetNotifyCallback: ((Result<Boolean>) -> Unit)? = null
    private var pendingReliableWriteCallback: ((Result<Boolean>) -> Unit)? = null
    private var currentReliableWriteValue: ByteArray? = null

    // Add lost-connection callback
    private var lostConnectionCallback: (() -> Unit)? = null
    fun setLostConnectionCallback(callback: () -> Unit) {
        lostConnectionCallback = callback
    }

    // Add BLE operation failure callback
    private var bleOperationFailedCallback: ((Exception) -> Unit)? = null
    fun setBleOperationFailedCallback(callback: (Exception) -> Unit) {
        bleOperationFailedCallback = callback
    }

    // Notification handler map
    private val notificationHandlers: MutableMap<UUID, (ByteArray) -> Unit> = mutableMapOf()
    fun registerNotificationHandler(uuid: UUID, handler: (ByteArray) -> Unit) {
        notificationHandlers[uuid] = handler
        Log.d("BluetoothDeviceManager", "Registered notification handler for $uuid")
    }
    fun unregisterNotificationHandler(uuid: UUID) {
        notificationHandlers.remove(uuid)
        Log.d("BluetoothDeviceManager", "Unregistered notification handler for $uuid")
    }

    // Add initial drain complete callback
    private var initialDrainCompleteCallback: (() -> Unit)? = null
    fun setInitialDrainCompleteCallback(callback: () -> Unit) {
        initialDrainCompleteCallback = callback
    }

    private var userInitiatedDisconnect = false

    // Add method to check if current disconnect was user-initiated
    fun isUserInitiatedDisconnect(): Boolean {
        val wasUserInitiated = userInitiatedDisconnect
        // Reset the flag after checking
        userInitiatedDisconnect = false
        return wasUserInitiated
    }

    private fun enqueueBleOperation(op: BleOperation) {
        bleQueue.add(op)
        processNextBleOperation()
    }

    private fun processNextBleOperation() {
        if (bleOperationInProgress || isStackSettling) return
        val op = bleQueue.poll() ?: return
        bleOperationInProgress = true
        currentOperation = op
        // Start timeout job
        currentOperationTimeoutJob = CoroutineScope(Dispatchers.Main).launch {
            delay(OPERATION_TIMEOUT_MS)
            onOperationTimeout()
        }
        try {
            when (op) {
                is BleOperation.Write -> {
                    val gatt = bluetoothGatt
                    val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
                    val toRadioChar = service?.getCharacteristic(TORADIO_CHARACTERISTIC_UUID)
                    if (gatt != null && service != null && toRadioChar != null) {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val result = gatt.writeCharacteristic(toRadioChar, op.data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                                Log.d("BluetoothDeviceManager", "[Queue] writeToRadio: writeCharacteristic (API33+) returned $result, bytes=${op.data.size}")
                                pendingWriteCallback = { lclResult ->
                                    try { completeCurrentOperation(lclResult); op.onResult(lclResult) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in write callback: ${e.message}") }
                                }
                            } else {
                                toRadioChar.setValue(op.data)
                                val result = gatt.writeCharacteristic(toRadioChar)
                                Log.d("BluetoothDeviceManager", "[Queue] writeToRadio: writeCharacteristic (legacy) returned $result, bytes=${op.data.size}")
                                pendingWriteCallback = { lclResult ->
                                    try { completeCurrentOperation(lclResult); op.onResult(lclResult) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in write callback: ${e.message}") }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("BluetoothDeviceManager", "Exception during writeCharacteristic: ${e.message}")
                            completeCurrentOperation(Result.failure<Boolean>(e))
                            op.onResult(Result.failure<Boolean>(e))
                            scheduleReconnect("Exception during writeCharacteristic")
                        }
                    } else {
                        Log.e("BluetoothDeviceManager", "[Queue] writeToRadio: GATT/service/char missing")
                        completeCurrentOperation(Result.failure<Boolean>(Exception("GATT/service/char missing")))
                        op.onResult(Result.failure<Boolean>(Exception("GATT/service/char missing")))
                    }
                }
                is BleOperation.Read -> {
                    val gatt = bluetoothGatt
                    if (gatt != null) {
                        try {
                            pendingReadCallback = { lclResult ->
                                try { completeCurrentOperation(lclResult); op.onResult(lclResult) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in read callback: ${e.message}") }
                            }
                            val result = gatt.readCharacteristic(op.characteristic)
                            Log.d("BluetoothDeviceManager", "[Queue] readCharacteristic: $result, uuid=${op.characteristic.uuid}")
                        } catch (e: Exception) {
                            Log.e("BluetoothDeviceManager", "Exception during readCharacteristic: ${e.message}")
                            completeCurrentOperation(Result.failure<ByteArray?>(e))
                            op.onResult(Result.failure<ByteArray?>(e))
                            scheduleReconnect("Exception during readCharacteristic")
                        }
                    } else {
                        Log.e("BluetoothDeviceManager", "[Queue] readCharacteristic: GATT missing")
                        completeCurrentOperation(Result.failure<ByteArray?>(Exception("GATT missing")))
                        op.onResult(Result.failure<ByteArray?>(Exception("GATT missing")))
                    }
                }
                is BleOperation.SetNotify -> {
                    val gatt = bluetoothGatt
                    if (gatt != null) {
                        try {
                            gatt.setCharacteristicNotification(op.characteristic, op.enable)
                            val descriptor = op.characteristic.getDescriptor(configurationDescriptorUUID)
                            if (descriptor != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    val writeResult = gatt.writeDescriptor(descriptor, if (op.enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
                                    Log.d("BluetoothDeviceManager", "[Queue] setNotify: (API33+) $writeResult, uuid=${op.characteristic.uuid}")
                                } else {
                                    descriptor.value = if (op.enable) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
                                    val writeResult = gatt.writeDescriptor(descriptor)
                                    Log.d("BluetoothDeviceManager", "[Queue] setNotify: (legacy) $writeResult, uuid=${op.characteristic.uuid}")
                                }
                                if (op.enable) {
                                    registerNotificationHandler(op.characteristic.uuid) { value ->
                                        try { Log.d("BluetoothDeviceManager", "Notification received for ${op.characteristic.uuid}: ${value.joinToString(", ")}") } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in notification handler: ${e.message}") }
                                    }
                                } else {
                                    unregisterNotificationHandler(op.characteristic.uuid)
                                }
                                pendingSetNotifyCallback = { notifyResult ->
                                    try { completeCurrentOperation(notifyResult); op.onResult(notifyResult) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in setNotify callback: ${e.message}") }
                                }
                            } else {
                                Log.e("BluetoothDeviceManager", "[Queue] setNotify: Descriptor missing")
                                completeCurrentOperation(Result.failure<Boolean>(Exception("Descriptor missing")))
                                op.onResult(Result.failure<Boolean>(Exception("Descriptor missing")))
                            }
                        } catch (e: Exception) {
                            Log.e("BluetoothDeviceManager", "Exception during setNotify: ${e.message}")
                            completeCurrentOperation(Result.failure<Boolean>(e))
                            op.onResult(Result.failure<Boolean>(e))
                            scheduleReconnect("Exception during setNotify")
                        }
                    } else {
                        Log.e("BluetoothDeviceManager", "[Queue] setNotify: GATT missing")
                        completeCurrentOperation(Result.failure<Boolean>(Exception("GATT missing")))
                        op.onResult(Result.failure<Boolean>(Exception("GATT missing")))
                    }
                }
                is BleOperation.ReliableWrite -> {
                    val gatt = bluetoothGatt
                    if (gatt != null) {
                        val charac = op.characteristic
                        try {
                            val started = gatt.beginReliableWrite()
                            if (!started) throw Exception("Failed to begin reliable write")
                            charac.setValue(op.value)
                            currentReliableWriteValue = op.value.clone()
                            val writeStarted = gatt.writeCharacteristic(charac)
                            if (!writeStarted) throw Exception("Failed to start reliable write")
                            pendingReliableWriteCallback = { reliableResult ->
                                try { completeCurrentOperation(reliableResult); op.onResult(reliableResult) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in reliableWrite callback: ${e.message}") }
                            }
                        } catch (e: Exception) {
                            Log.e("BluetoothDeviceManager", "Reliable write failed to start: ${e.message}")
                            if ((op as? BleOperation.ReliableWrite)?.attempt ?: 1 < 3) {
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(200)
                                    enqueueBleOperation(BleOperation.ReliableWrite(charac, op.value, op.onResult, (op.attempt ?: 1) + 1))
                                }
                                completeCurrentOperation(Result.failure<Boolean>(e))
                            } else {
                                completeCurrentOperation(Result.failure<Boolean>(e))
                                op.onResult(Result.failure<Boolean>(e))
                                scheduleReconnect("Exception during reliableWrite")
                            }
                        }
                    } else {
                        Log.e("BluetoothDeviceManager", "[Queue] reliableWrite: GATT missing")
                        completeCurrentOperation(Result.failure<Boolean>(Exception("GATT missing")))
                        op.onResult(Result.failure<Boolean>(Exception("GATT missing")))
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("BluetoothDeviceManager", "Exception in processNextBleOperation: ${e.message}")
            completeCurrentOperation(Result.failure<Any?>(e))
            scheduleReconnect("Exception in processNextBleOperation")
        }
    }

    private fun onOperationTimeout() {
        val op = currentOperation
        Log.e("BluetoothDeviceManager", "BLE operation timed out: $op")
        
        val timeoutException = Exception("Operation timed out")
        
        // Notify the callback about the timeout
        bleOperationFailedCallback?.invoke(timeoutException)
        
        when (op) {
            is BleOperation.Write -> op.onResult(Result.failure<Boolean>(timeoutException))
            is BleOperation.Read -> op.onResult(Result.failure<ByteArray?>(timeoutException))
            is BleOperation.SetNotify -> op.onResult(Result.failure<Boolean>(timeoutException))
            is BleOperation.ReliableWrite -> op.onResult(Result.failure<Boolean>(timeoutException))
            null -> {}
        }
        completeCurrentOperation(Result.failure<Any?>(timeoutException))
        bleOperationInProgress = false
        processNextBleOperation()
    }

    private fun completeCurrentOperation(result: Result<*>) {
        currentOperationTimeoutJob?.cancel()
        currentOperationTimeoutJob = null
        currentOperation = null
        bleOperationInProgress = false
        processNextBleOperation()
    }

    private fun failAllPendingOperations(error: Exception) {
        Log.w("BluetoothDeviceManager", "Failing all pending BLE operations: ${error.message}")
        
        // Notify the callback about the failure
        bleOperationFailedCallback?.invoke(error)
        
        while (true) {
            val op = bleQueue.poll() ?: break
            when (op) {
                is BleOperation.Write -> op.onResult(Result.failure<Boolean>(error))
                is BleOperation.Read -> op.onResult(Result.failure<ByteArray?>(error))
                is BleOperation.SetNotify -> op.onResult(Result.failure<Boolean>(error))
                is BleOperation.ReliableWrite -> op.onResult(Result.failure<Boolean>(error))
            }
        }
        completeCurrentOperation(Result.failure<Any?>(error))
    }

    fun scanForDevices(serviceUuid: UUID, onResult: (BluetoothDevice) -> Unit, onScanFinished: () -> Unit) {
        Log.d("BluetoothDeviceManager", "Starting device scan")
        
        // Clean up any existing state before starting a new scan
        resetReconnectState()
        
        // Add a delay to ensure the Bluetooth stack has time to reset
        CoroutineScope(Dispatchers.Main).launch {
            delay(2000) // 2 second delay to ensure cleanup is complete
            
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val discovered = mutableSetOf<String>()
            
            // First, check for already-connected devices that support the Meshtastic service
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            if (connectedDevices.isNotEmpty()) {
                Log.i("BluetoothDeviceManager", "Found ${connectedDevices.size} OS-level connections, checking for Meshtastic service support")
                
                for (device in connectedDevices) {
                    if (device.address !in discovered) {
                        Log.d("BluetoothDeviceManager", "Checking connected device: ${device.name ?: "Unknown"} (${device.address})")
                        
                        // Try to connect to the device temporarily to check if it supports the Meshtastic service
                        val tempCallback = object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                if (newState == BluetoothProfile.STATE_CONNECTED) {
                                    Log.d("BluetoothDeviceManager", "Connected to ${device.address} for service check")
                                    gatt.discoverServices()
                                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                    Log.d("BluetoothDeviceManager", "Disconnected from ${device.address} after service check")
                                    gatt.close()
                                }
                            }
                            
                            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                if (status == BluetoothGatt.GATT_SUCCESS) {
                                    val service = gatt.getService(serviceUuid)
                                    if (service != null) {
                                        Log.i("BluetoothDeviceManager", "Found Meshtastic service on connected device: ${device.name ?: "Unknown"} (${device.address})")
                                        discovered.add(device.address)
                                        onResult(device)
                                    } else {
                                        Log.d("BluetoothDeviceManager", "Connected device ${device.address} does not support Meshtastic service")
                                    }
                                } else {
                                    Log.w("BluetoothDeviceManager", "Service discovery failed for connected device ${device.address}: $status")
                                }
                                // Always disconnect after service check
                                gatt.disconnect()
                            }
                        }
                        
                        try {
                            // Connect with autoConnect=false to avoid interfering with existing connection
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                device.connectGatt(context, false, tempCallback, BluetoothDevice.TRANSPORT_LE)
                            } else {
                                device.connectGatt(context, false, tempCallback)
                            }
                            
                            // Wait a bit for the service check to complete
                            delay(1000)
                        } catch (e: Exception) {
                            Log.w("BluetoothDeviceManager", "Error checking connected device ${device.address}: ${e.message}")
                        }
                    }
                }
            } else {
                Log.d("BluetoothDeviceManager", "No OS-level connections found")
            }
            
            val adapter = bluetoothManager.adapter ?: return@launch
            val bleScanner = adapter.bluetoothLeScanner
            val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
            val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

            val scanCallback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    if (device.address !in discovered) {
                        discovered.add(device.address)
                        Log.d("BluetoothDeviceManager", "Found device via scan: ${device.name ?: "Unknown"} (${device.address})")
                        onResult(device)
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    Log.e("BluetoothDeviceManager", "BLE scan failed: $errorCode")
                    onScanFinished()
                }
            }
            
            Log.d("BluetoothDeviceManager", "Starting BLE scan for additional devices...")
            bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
            
            // Scan for 4 seconds
            delay(4000)
            bleScanner.stopScan(scanCallback)
            Log.d("BluetoothDeviceManager", "BLE scan completed, found ${discovered.size} total devices")
            onScanFinished()
        }
    }

    fun connect(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        lastDevice = device
        lastOnConnected = onConnected
        reconnectAttempts = 0
        isReconnectionAttempt = false // Reset for fresh connections
        mtuRetry = false
        skipRefresh = shouldSkipRefresh(device)
        connectInternal(device, onConnected)
    }

    private fun connectInternal(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        _connectionState.value = ConnectionState.Connecting
        gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d("BluetoothDeviceManager", "onConnectionStateChange: status=$status, newState=$newState")
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    bluetoothGatt = gatt
                    CoroutineScope(Dispatchers.Main).launch {
                        if (shouldForceRefresh(device)) {
                            try {
                                val refreshMethod = gatt.javaClass.getMethod("refresh")
                                val result = refreshMethod.invoke(gatt) as Boolean
                                Log.d("BluetoothDeviceManager", "[ForceRefresh] Forced gatt.refresh() for ESP32/device: result=$result")
                            } catch (e: Exception) {
                                Log.e("BluetoothDeviceManager", "[ForceRefresh] Failed to call gatt.refresh() for ESP32/device: ${e.message}")
                            }
                            delay(500)
                        } else if (!shouldSkipRefresh(device)) {
                            try {
                                val refreshMethod = gatt.javaClass.getMethod("refresh")
                                val result = refreshMethod.invoke(gatt) as Boolean
                                Log.d("BluetoothDeviceManager", "[ForceRefresh] Called gatt.refresh(): result=$result")
                            } catch (e: Exception) {
                                Log.e("BluetoothDeviceManager", "[ForceRefresh] Failed to call gatt.refresh(): ${e.message}")
                            }
                            delay(500)
                        } else {
                            Log.d("BluetoothDeviceManager", "[ForceRefresh] Skipping gatt.refresh() for this device type.")
                        }
                        val mtuRequested = gatt.requestMtu(512)
                        Log.d("BluetoothDeviceManager", "Requested MTU 512: success=$mtuRequested")
                        // Wait for onMtuChanged before calling discoverServices
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    bluetoothGatt = null
                    _connectionState.value = ConnectionState.Disconnected
                    CoroutineScope(Dispatchers.Main).launch { onConnected(false) }
                    failAllPendingOperations(Exception("Disconnected"))
                    lostConnectionCallback?.invoke()
                    // BLE stack bug handling
                    if (userInitiatedDisconnect) {
                        Log.i("BluetoothDeviceManager", "User-initiated disconnect; not scheduling reconnect.")
                        userInitiatedDisconnect = false
                        return
                    }
                    when (status) {
                        133 -> {
                            // Immediate connection failed, switch to background connection
                            Log.w("BluetoothDeviceManager", "Immediate connection failed (status 133), switching to background connection")
                            gatt.close()
                            // Retry with autoConnect=true for background connection
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000) // Brief delay before background connection attempt
                                try {
                                    device.connectGattBackground(context, gattCallback!!)
                                } catch (e: Exception) {
                                    Log.e("BluetoothDeviceManager", "Background connection failed: ${e.message}")
                                    _connectionState.value = ConnectionState.Failed("Background connection failed")
                                    onConnected(false)
                                }
                            }
                        }
                        147 -> {
                            Log.e("BluetoothDeviceManager", "Status 147 encountered. Treating as lost connection and scheduling reconnect.")
                            scheduleReconnect("Status 147 (lost connection)")
                        }
                        257 -> {
                            Log.e("BluetoothDeviceManager", "Status 257 encountered. Restarting BLE stack.")
                            restartBleStack()
                        }
                        else -> {
                            Log.w("BluetoothDeviceManager", "Disconnected with status $status. Letting client handle reconnection.")
                            // Let the client handle reconnection instead of automatic scheduling
                            lostConnectionCallback?.invoke()
                        }
                    }
                }
            }

            override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                Log.d("BluetoothDeviceManager", "MTU changed: mtu=$mtu, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    mtuRetry = false
                    gatt.discoverServices()
                } else if (!mtuRetry) {
                    mtuRetry = true
                    Log.e("BluetoothDeviceManager", "MTU change failed, retrying once.")
                    val mtuRequested = gatt.requestMtu(512)
                    Log.d("BluetoothDeviceManager", "Retried MTU 512: success=$mtuRequested")
                } else {
                    Log.e("BluetoothDeviceManager", "MTU change failed after retry, proceeding to discoverServices anyway.")
                    gatt.discoverServices()
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                CoroutineScope(Dispatchers.Main).launch {
                    Log.d("BluetoothDeviceManager", "Service discovery completed with status: $status")
                    
                    // Use longer initial delay for reconnection attempts (device may have been powered off)
                    val initialDelay = if (isReconnectionAttempt) 2000L else 1000L
                    Log.d("BluetoothDeviceManager", "Waiting ${initialDelay}ms after service discovery (reconnection: $isReconnectionAttempt)")
                    delay(initialDelay)
                    
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        // Try to find characteristics with retry logic
                        var fromNumChar: BluetoothGattCharacteristic? = null
                        var fromRadioChar: BluetoothGattCharacteristic? = null
                        var retryCount = 0
                        val maxRetries = if (isReconnectionAttempt) 5 else 3 // More retries for reconnection
                        
                        while (retryCount < maxRetries && (fromNumChar == null || fromRadioChar == null)) {
                            val service = gatt.getService(MESHTASTIC_SERVICE_UUID)
                            if (service == null) {
                                Log.w("BluetoothDeviceManager", "Meshtastic service not found on attempt ${retryCount + 1}")
                            } else {
                                Log.d("BluetoothDeviceManager", "Found Meshtastic service, looking for characteristics...")
                                fromNumChar = service.getCharacteristic(FROMNUM_CHARACTERISTIC_UUID)
                                fromRadioChar = service.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
                                
                                if (fromNumChar != null) {
                                    Log.d("BluetoothDeviceManager", "Found FROMNUM characteristic")
                                }
                                if (fromRadioChar != null) {
                                    Log.d("BluetoothDeviceManager", "Found FROMRADIO characteristic")
                                }
                            }
                            
                            if (fromNumChar == null || fromRadioChar == null) {
                                retryCount++
                                if (retryCount < maxRetries) {
                                    val retryDelay = if (isReconnectionAttempt) retryCount * 1500L else retryCount * 1000L
                                    Log.w("BluetoothDeviceManager", "Characteristics not found on attempt $retryCount, retrying in ${retryDelay}ms...")
                                    delay(retryDelay) // Progressive delay: longer for reconnection attempts
                                }
                            }
                        }
                        
                        if (fromNumChar != null && fromRadioChar != null) {
                            Log.i("BluetoothDeviceManager", "All characteristics found successfully after ${retryCount + 1} attempts")
                            isReconnectionAttempt = false // Reset the flag on successful connection
                            // Do NOT subscribe to FROMNUM notifications here anymore
                            _connectionState.value = ConnectionState.Connected(device)
                            onConnected(true)
                            // Start initial drain of FROMRADIO after a short delay
                            initialDrainDone = false
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(200) // Short delay to let notifications settle
                                aggressiveDrainFromRadio(gatt, fromRadioChar)
                                // Notify initial drain complete after the drain finishes
                                delay(200) // Give a bit more time for drain to finish
                                initialDrainCompleteCallback?.invoke()
                            }
                        } else {
                            if (fromNumChar == null) Log.e("BluetoothDeviceManager", "FROMNUM characteristic not found after $maxRetries attempts!")
                            if (fromRadioChar == null) Log.e("BluetoothDeviceManager", "FROMRADIO characteristic not found after $maxRetries attempts!")
                            _connectionState.value = ConnectionState.Failed("Missing characteristic after service discovery")
                            onConnected(false)
                            scheduleReconnect("Missing characteristic after service discovery")
                        }
                    } else {
                        Log.e("BluetoothDeviceManager", "Service discovery failed with status: $status")
                        _connectionState.value = ConnectionState.Failed("Service discovery failed")
                        onConnected(false)
                        scheduleReconnect("Service discovery failed")
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                Log.d("BluetoothDeviceManager", "onCharacteristicChanged: uuid=${characteristic.uuid}, value=${characteristic.getValue()?.joinToString(", ")}")
                val handler = notificationHandlers[characteristic.uuid]
                if (handler != null) {
                    handler(characteristic.getValue() ?: ByteArray(0))
                } else {
                    Log.w("BluetoothDeviceManager", "Notification received for ${characteristic.uuid}, but no handler registered.")
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                try {
                    Log.d("BluetoothDeviceManager", "onCharacteristicRead: uuid=${characteristic.uuid}, status=$status, value=${characteristic.getValue()?.joinToString(", ")}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        try { pendingReadCallback?.invoke(Result.success(characteristic.getValue())) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in read callback: ${e.message}") }
                        pendingReadCallback = null
                        completeCurrentOperation(Result.success(characteristic.getValue()))
                    } else {
                        // Retry logic for read failures
                        val currentOp = currentOperation as? BleOperation.Read
                        if (currentOp != null && (currentOp.attempt ?: 1) < MAX_READ_ATTEMPTS) {
                            Log.w("BluetoothDeviceManager", "Read failed for ${characteristic.uuid}, retrying attempt ${(currentOp.attempt ?: 1) + 1}")
                            // Re-enqueue with incremented attempt
                            enqueueBleOperation(BleOperation.Read(currentOp.characteristic, currentOp.onResult, (currentOp.attempt ?: 1) + 1))
                        } else {
                            Log.e("BluetoothDeviceManager", "Characteristic read failed after $MAX_READ_ATTEMPTS attempts for ${characteristic.uuid}, triggering reconnect.")
                            try { pendingReadCallback?.invoke(Result.failure(Exception("Characteristic read failed after $MAX_READ_ATTEMPTS attempts"))) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in read failure callback: ${e.message}") }
                            scheduleReconnect("Repeated read failures on ${characteristic.uuid}")
                        }
                        pendingReadCallback = null
                        completeCurrentOperation(Result.failure<Any?>(Exception("Characteristic read failed with status $status")))
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothDeviceManager", "Exception in onCharacteristicRead: ${e.message}")
                    completeCurrentOperation(Result.failure<Any?>(e))
                    scheduleReconnect("Exception in onCharacteristicRead")
                }
            }

            // Android 13+ (API 33+) version
            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                try {
                    Log.d("BluetoothDeviceManager", "onCharacteristicRead (API33+): uuid=${characteristic.uuid}, status=$status, value=${value.joinToString(", ")}")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        try { pendingReadCallback?.invoke(Result.success(value)) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in read callback: ${e.message}") }
                        pendingReadCallback = null
                        completeCurrentOperation(Result.success(value))
                    } else {
                        // Retry logic for read failures
                        val currentOp = currentOperation as? BleOperation.Read
                        if (currentOp != null && (currentOp.attempt ?: 1) < MAX_READ_ATTEMPTS) {
                            Log.w("BluetoothDeviceManager", "Read failed for ${characteristic.uuid}, retrying attempt ${(currentOp.attempt ?: 1) + 1}")
                            // Re-enqueue with incremented attempt
                            enqueueBleOperation(BleOperation.Read(currentOp.characteristic, currentOp.onResult, (currentOp.attempt ?: 1) + 1))
                        } else {
                            Log.e("BluetoothDeviceManager", "Characteristic read failed after $MAX_READ_ATTEMPTS attempts for ${characteristic.uuid}, triggering reconnect.")
                            try { pendingReadCallback?.invoke(Result.failure(Exception("Characteristic read failed after $MAX_READ_ATTEMPTS attempts"))) } catch (e: Exception) { Log.e("BluetoothDeviceManager", "Exception in read failure callback: ${e.message}") }
                            scheduleReconnect("Repeated read failures on ${characteristic.uuid}")
                        }
                        pendingReadCallback = null
                        completeCurrentOperation(Result.failure<Any?>(Exception("Characteristic read failed with status $status")))
                    }
                } catch (e: Exception) {
                    Log.e("BluetoothDeviceManager", "Exception in onCharacteristicRead (API33+): ${e.message}")
                    completeCurrentOperation(Result.failure<Any?>(e))
                    scheduleReconnect("Exception in onCharacteristicRead (API33+)")
                }
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
                Log.d("BluetoothDeviceManager", "onDescriptorWrite: uuid=${descriptor.uuid}, status=$status")
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    pendingSetNotifyCallback?.invoke(Result.success(true))
                } else {
                    pendingSetNotifyCallback?.invoke(Result.failure<Boolean>(Exception("Descriptor write failed with status $status")))
                }
                pendingSetNotifyCallback = null
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                Log.d("BluetoothDeviceManager", "onCharacteristicWrite: uuid=${characteristic.uuid}, status=$status")
                if (currentReliableWriteValue != null) {
                    // Reliable write in progress
                    if (status == BluetoothGatt.GATT_SUCCESS && characteristic.getValue().contentEquals(currentReliableWriteValue)) {
                        val execResult = gatt.executeReliableWrite()
                        Log.d("BluetoothDeviceManager", "executeReliableWrite called: $execResult")
                        // Wait for onReliableWriteCompleted
                    } else {
                        Log.e("BluetoothDeviceManager", "Reliable write failed or value mismatch, aborting.")
                        gatt.abortReliableWrite()
                        pendingReliableWriteCallback?.invoke(Result.failure<Boolean>(Exception("Reliable write failed or value mismatch")))
                        pendingReliableWriteCallback = null
                        currentReliableWriteValue = null
                        completeCurrentOperation(Result.failure<Boolean>(Exception("Reliable write failed or value mismatch")))
                        bleOperationInProgress = false
                    }
                } else {
                    // Standard write
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        pendingWriteCallback?.invoke(Result.success(true))
                    } else {
                        pendingWriteCallback?.invoke(Result.failure<Boolean>(Exception("Characteristic write failed with status $status")))
                    }
                    pendingWriteCallback = null
                    // completeCurrentOperation is called in the callback
                }
            }

            override fun onReliableWriteCompleted(gatt: BluetoothGatt, status: Int) {
                Log.d("BluetoothDeviceManager", "onReliableWriteCompleted: status=$status")
                if (pendingReliableWriteCallback != null) {
                    pendingReliableWriteCallback?.invoke(Result.success(status == BluetoothGatt.GATT_SUCCESS))
                    pendingReliableWriteCallback = null
                    currentReliableWriteValue = null
                }
                completeCurrentOperation(Result.success(status == BluetoothGatt.GATT_SUCCESS))
                bleOperationInProgress = false
            }
        }
        
        // Start the connection with immediate strategy (autoConnect=false)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            try {
                device.connectGattImmediate(context, gattCallback!!)
            } catch (e: Exception) {
                Log.e("BluetoothDeviceManager", "Failed to start connection: ${e.message}")
                _connectionState.value = ConnectionState.Failed("Failed to start connection")
                onConnected(false)
            }
        } else {
            val bondReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    val bondedDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && bondedDevice?.address == device.address) {
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            context.unregisterReceiver(this)
                            try {
                                device.connectGattImmediate(context, gattCallback!!)
                            } catch (e: Exception) {
                                Log.e("BluetoothDeviceManager", "Failed to start connection after bonding: ${e.message}")
                                _connectionState.value = ConnectionState.Failed("Failed to start connection after bonding")
                                onConnected(false)
                            }
                        } else if (bondState == BluetoothDevice.BOND_NONE) {
                            context.unregisterReceiver(this)
                            _connectionState.value = ConnectionState.Failed("Bonding failed")
                            CoroutineScope(Dispatchers.Main).launch { onConnected(false) }
                        }
                    }
                }
            }
            val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            context.registerReceiver(bondReceiver, filter)
            device.createBond()
        }
    }

    fun disconnect() {
        Log.i("BluetoothDeviceManager", "User-initiated disconnect")
        userInitiatedDisconnect = true
        forceCleanup() // Use force cleanup for user-initiated disconnects
        _connectionState.value = ConnectionState.Disconnected
    }

    /**
     * Force a complete reset of the Bluetooth state
     * This should be called when the user wants to start completely fresh
     */
    fun forceReset() {
        Log.i("BluetoothDeviceManager", "Force reset requested")
        userInitiatedDisconnect = true
        forceCleanup()
        
        // Additional reset: clear all state and wait for Bluetooth stack to settle
        CoroutineScope(Dispatchers.Main).launch {
            delay(3000) // Wait 3 seconds for complete reset
            Log.d("BluetoothDeviceManager", "Force reset completed")
        }
    }

    /**
     * Nuclear option: Force a complete Bluetooth adapter reset
     * This should only be used when the OS Bluetooth stack is completely stuck
     * WARNING: This will disconnect ALL Bluetooth devices and restart the Bluetooth stack
     */
    fun forceBluetoothAdapterReset() {
        Log.w("BluetoothDeviceManager", "NUCLEAR OPTION: Force Bluetooth adapter reset")
        
        try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = bluetoothManager.adapter
            
            if (adapter != null && adapter.isEnabled) {
                Log.i("BluetoothDeviceManager", "Disabling Bluetooth adapter...")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    adapter.disable()
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i("BluetoothDeviceManager", "Re-enabling Bluetooth adapter...")
                        adapter.enable()
                    }, 3000) // Wait 3 seconds before re-enabling
                } else {
                    @Suppress("DEPRECATION")
                    adapter.disable()
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.i("BluetoothDeviceManager", "Re-enabling Bluetooth adapter...")
                        @Suppress("DEPRECATION")
                        adapter.enable()
                    }, 3000) // Wait 3 seconds before re-enabling
                }
                
                // Reset our state
                resetReconnectState()
            }
        } catch (e: Exception) {
            Log.e("BluetoothDeviceManager", "Error during Bluetooth adapter reset: ${e.message}")
        }
    }

    // Robust reconnect logic
    private fun scheduleReconnect(reason: String) {
        // Only allow one pending reconnect, and limit attempts
        if (pendingReconnect) {
            Log.e("BluetoothDeviceManager", "Reconnect already pending, skipping: $reason")
            return
        }

        // Check if we've exceeded the maximum reconnection time
        val currentTime = System.currentTimeMillis()
        if (reconnectStartTime == 0L) {
            reconnectStartTime = currentTime
        } else if (currentTime - reconnectStartTime > MAX_RECONNECT_TIME_MS) {
            Log.e("BluetoothDeviceManager", "Reconnect timeout exceeded after ${currentTime - reconnectStartTime}ms")
            _connectionState.value = ConnectionState.Failed("Connection timeout - device may be unavailable")
            lastOnConnected?.invoke(false)
            forceCleanup() // Force cleanup when timeout is exceeded
            return
        }

        // Check if we've exceeded maximum attempts
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e("BluetoothDeviceManager", "Maximum reconnect attempts reached")
            _connectionState.value = ConnectionState.Failed("Maximum reconnection attempts reached")
            lastOnConnected?.invoke(false)
            forceCleanup() // Force cleanup when max attempts are reached
            return
        }

        // Check if the OS is stuck in a connected state (this often happens after device power cycles)
        lastDevice?.let { device ->
            if (isDeviceConnectedAtOsLevel(device) && reconnectAttempts >= 2) {
                Log.w("BluetoothDeviceManager", "OS appears stuck in connected state, forcing connection release")
                forceConnectionRelease(device)
                // Wait longer before next attempt after connection release
                CoroutineScope(Dispatchers.Main).launch {
                    delay(5000) // Wait 5 seconds after connection release
                    pendingReconnect = false
                    lastDevice?.let { device ->
                        lastOnConnected?.let { onConnected ->
                            Log.i("BluetoothDeviceManager", "Attempting reconnect after connection release to ${device.address}")
                            connectInternal(device, onConnected)
                        }
                    }
                }
                return
            }
        }

        pendingReconnect = true
        reconnectAttempts++
        isReconnectionAttempt = true // Mark this as a reconnection attempt
        
        // Progressive delay strategy: longer delays for early attempts, then exponential backoff
        val delayMs = when (reconnectAttempts) {
            1 -> 2000L  // 2 seconds for first attempt
            2 -> 4000L  // 4 seconds for second attempt  
            3 -> 6000L  // 6 seconds for third attempt
            else -> (1000L * reconnectAttempts).coerceAtMost(10000L) // Exponential backoff with max 10s
        }
        
        Log.w("BluetoothDeviceManager", "[Reconnect] Scheduling reconnect in ${delayMs}ms because: $reason (attempt $reconnectAttempts)")
        CoroutineScope(Dispatchers.Main).launch {
            delay(delayMs)
            pendingReconnect = false
            lastDevice?.let { device ->
                lastOnConnected?.let { onConnected ->
                    Log.i("BluetoothDeviceManager", "[Reconnect] Attempting reconnect to ${device.address} (attempt $reconnectAttempts)")
                    connectInternal(device, onConnected)
                }
            }
        }
    }

    private fun resetReconnectState() {
        Log.d("BluetoothDeviceManager", "Resetting reconnection state")
        reconnectAttempts = 0
        reconnectStartTime = 0
        pendingReconnect = false
        isReconnectionAttempt = false // Reset reconnection flag
        lastDevice = null
        lastOnConnected = null
        
        // Force cleanup GATT resources with retry
        forceCleanupGatt()
        
        // Clear any pending operations
        failAllPendingOperations(Exception("Connection reset"))
        bleQueue.clear()
        bleOperationInProgress = false
        currentOperation = null
        currentOperationTimeoutJob?.cancel()
        currentOperationTimeoutJob = null
        
        // Reset other state
        mtuRetry = false
        skipRefresh = false
        initialDrainDone = false
        pendingWriteCallback = null
        pendingReadCallback = null
        pendingSetNotifyCallback = null
        pendingReliableWriteCallback = null
        currentReliableWriteValue = null
        notificationHandlers.clear()
        
        // Reset connection state
        _connectionState.value = ConnectionState.Disconnected
        
        Log.d("BluetoothDeviceManager", "Reconnection state reset completed")
    }

    /**
     * Force cleanup of GATT connection with retry logic
     */
    private fun forceCleanupGatt() {
        val gatt = bluetoothGatt
        if (gatt != null) {
            Log.d("BluetoothDeviceManager", "Force cleaning up GATT connection")
            try {
                // First try to disconnect
                gatt.disconnect()
                Log.d("BluetoothDeviceManager", "GATT disconnect called")
            } catch (e: Exception) {
                Log.w("BluetoothDeviceManager", "Error during GATT disconnect: ${e.message}")
            }
            
            try {
                // Then close the connection
                gatt.close()
                Log.d("BluetoothDeviceManager", "GATT close called")
            } catch (e: Exception) {
                Log.w("BluetoothDeviceManager", "Error during GATT close: ${e.message}")
            }
            
            // Clear the reference
            bluetoothGatt = null
            gattCallback = null
        }
    }

    /**
     * Force the OS Bluetooth stack to release the connection without removing the bond
     * This is necessary when the OS gets stuck in a connected state
     */
    private fun forceOsBluetoothReset(device: BluetoothDevice) {
        Log.i("BluetoothDeviceManager", "Force OS Bluetooth reset for device: ${device.address}")
        
        try {
            // Instead of removing the bond, try to force the OS to release the connection
            // by creating a new GATT connection with autoConnect=false
            Log.d("BluetoothDeviceManager", "Attempting to force OS connection release without unbonding")
            
            // First, ensure our current GATT is properly closed
            forceCleanupGatt()
            
            // Wait a bit for the OS to process the cleanup
            CoroutineScope(Dispatchers.Main).launch {
                delay(2000) // Wait 2 seconds for OS cleanup
                Log.d("BluetoothDeviceManager", "OS cleanup delay completed")
            }
            
        } catch (e: Exception) {
            Log.w("BluetoothDeviceManager", "Error during force OS reset: ${e.message}")
        }
    }

    /**
     * Alternative approach: Force connection release by connecting with autoConnect=false
     * This can help when the OS is stuck in a connected state
     */
    private fun forceConnectionRelease(device: BluetoothDevice) {
        Log.i("BluetoothDeviceManager", "Force connection release for device: ${device.address}")
        
        try {
            // Method 1: Try to force a fresh connection with autoConnect=false
            val tempCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                    Log.d("BluetoothDeviceManager", "Temp connection state change: status=$status, newState=$newState")
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // Immediately disconnect to release the connection
                        Log.d("BluetoothDeviceManager", "Temp connection established, immediately disconnecting")
                        gatt.disconnect()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        // Close the temporary connection
                        gatt.close()
                        Log.d("BluetoothDeviceManager", "Temp connection cleanup completed")
                    }
                }
            }
            
            // Connect with autoConnect=false to force a fresh connection
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, tempCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, tempCallback)
            }
            
            // Method 2: Also try to refresh the GATT connection if available
            CoroutineScope(Dispatchers.Main).launch {
                delay(1000) // Wait a bit for the temp connection to establish
                try {
                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
                    val connectedDevice = connectedDevices.find { it.address == device.address }
                    
                    if (connectedDevice != null) {
                        Log.d("BluetoothDeviceManager", "Attempting to refresh GATT connection")
                        // Try to get the GATT server and refresh it
                        val gattServer = bluetoothManager.openGattServer(context, null)
                        if (gattServer != null) {
                            try {
                                val refreshMethod = gattServer.javaClass.getMethod("refresh")
                                val result = refreshMethod.invoke(gattServer) as Boolean
                                Log.d("BluetoothDeviceManager", "GATT server refresh result: $result")
                            } catch (e: Exception) {
                                Log.w("BluetoothDeviceManager", "GATT server refresh not available: ${e.message}")
                            } finally {
                                gattServer.close()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("BluetoothDeviceManager", "Error during GATT refresh attempt: ${e.message}")
                }
            }
            
        } catch (e: Exception) {
            Log.w("BluetoothDeviceManager", "Error during force connection release: ${e.message}")
        }
    }

    /**
     * Check if the device is still connected at the OS level
     */
    fun isDeviceConnectedAtOsLevel(device: BluetoothDevice): Boolean {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            connectedDevices.any { it.address == device.address }
        } catch (e: Exception) {
            Log.w("BluetoothDeviceManager", "Error checking OS connection status: ${e.message}")
            false
        }
    }

    /**
     * Get a list of all connected devices that support the Meshtastic service
     * @param serviceUuid The service UUID to check for
     * @return List of connected devices that support the service
     */
    fun getConnectedMeshtasticDevices(serviceUuid: UUID): List<BluetoothDevice> {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val meshtasticDevices = mutableListOf<BluetoothDevice>()
            
            for (device in connectedDevices) {
                // Check if this device supports the Meshtastic service
                val tempCallback = object : BluetoothGattCallback() {
                    var hasService = false
                    
                    override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            gatt.discoverServices()
                        } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                            gatt.close()
                        }
                    }
                    
                    override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                        if (status == BluetoothGatt.GATT_SUCCESS) {
                            val service = gatt.getService(serviceUuid)
                            hasService = service != null
                            if (hasService) {
                                meshtasticDevices.add(device)
                            }
                        }
                        gatt.disconnect()
                    }
                }
                
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        device.connectGatt(context, false, tempCallback, BluetoothDevice.TRANSPORT_LE)
                    } else {
                        device.connectGatt(context, false, tempCallback)
                    }
                } catch (e: Exception) {
                    Log.w("BluetoothDeviceManager", "Error checking device ${device.address}: ${e.message}")
                }
            }
            
            meshtasticDevices
        } catch (e: Exception) {
            Log.w("BluetoothDeviceManager", "Error getting connected Meshtastic devices: ${e.message}")
            emptyList()
        }
    }

    /**
     * Force cleanup when reconnection fails completely
     * This should be called when the user wants to start fresh
     */
    fun forceCleanup() {
        Log.i("BluetoothDeviceManager", "Force cleanup requested")
        resetReconnectState()
        
        // Check if we need to force OS-level cleanup
        lastDevice?.let { device ->
            if (isDeviceConnectedAtOsLevel(device)) {
                Log.w("BluetoothDeviceManager", "Device still connected at OS level, forcing connection release")
                forceConnectionRelease(device)
            }
        }
        
        // Additional cleanup: cancel any pending operations and clear state
        CoroutineScope(Dispatchers.Main).launch {
            delay(1000) // Give some time for cleanup to complete
            Log.d("BluetoothDeviceManager", "Force cleanup completed")
        }
    }

    /**
     * Manually force the release of a device connection without removing the bond
     * This can be called when the OS appears stuck in a connected state
     * @param device The device to force connection release for
     */
    fun forceDeviceConnectionRelease(device: BluetoothDevice) {
        Log.i("BluetoothDeviceManager", "Manual force connection release requested for: ${device.address}")
        forceConnectionRelease(device)
    }

    // Device-specific refresh logic
    private fun shouldForceRefresh(device: BluetoothDevice): Boolean {
        val name = device.name ?: ""
        val address = device.address ?: ""
        // Heuristic: ESP32 devices often have names containing "ESP32" or addresses starting with "FD:10:04"
        val isESP32 = name.contains("ESP32", ignoreCase = true) || address.startsWith("FD:10:04")
        // Extend this logic as needed for other device types
        return isESP32
    }

    private fun shouldSkipRefresh(device: BluetoothDevice): Boolean {
        val name = device.name ?: ""
        val isNRF52 = name.contains("NRF52", ignoreCase = true)
        val isAmazon = Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        return isNRF52 || isAmazon
    }

    // FROMNUM write for packet recovery (API only)
    /**
     * Write a value to the FROMNUM characteristic to recover dropped packets.
     * This should be called by higher layers if a packet is missed (see Meshtastic protocol docs).
     * @param value The packet number to recover from.
     * @return true if the write was initiated, false otherwise.
     */
    fun writeFromNum(value: Int): Boolean {
        val gatt = bluetoothGatt ?: return false
        val service = gatt.getService(MESHTASTIC_SERVICE_UUID) ?: return false
        val fromNumChar = service.getCharacteristic(FROMNUM_CHARACTERISTIC_UUID) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val intBytes = ByteArray(4) { i -> ((value shr (i * 8)) and 0xFF).toByte() }
            val result = gatt.writeCharacteristic(fromNumChar, intBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            Log.d("BluetoothDeviceManager", "[PacketRecovery] Wrote $value to FROMNUM for packet recovery (API33+): success=$result")
            return result == BluetoothGatt.GATT_SUCCESS
        } else {
            fromNumChar.setValue(value, BluetoothGattCharacteristic.FORMAT_UINT32, 0)
            val result = gatt.writeCharacteristic(fromNumChar)
            Log.d("BluetoothDeviceManager", "[PacketRecovery] Wrote $value to FROMNUM for packet recovery (legacy): result=$result")
            return result
        }
    }

    // Use autoconnect in connectGatt
    private fun BluetoothDevice.connectGatt(context: Context, gattCallback: BluetoothGattCallback): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            this.connectGatt(context, true, gattCallback)
        }
    }

    // Updated connection strategy: start with autoConnect=false, fallback to autoConnect=true
    private fun BluetoothDevice.connectGattImmediate(context: Context, gattCallback: BluetoothGattCallback): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE) // autoConnect=false for immediate feedback
        } else {
            this.connectGatt(context, false, gattCallback) // autoConnect=false for immediate feedback
        }
    }

    private fun BluetoothDevice.connectGattBackground(context: Context, gattCallback: BluetoothGattCallback): BluetoothGatt {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE) // autoConnect=true for background connection
        } else {
            this.connectGatt(context, true, gattCallback) // autoConnect=true for background connection
        }
    }

    // Aggressive drain logic and isDrainingFromRadio flag
    @Volatile private var isDrainingFromRadio = false
    @Volatile private var isStackSettling = false
    fun aggressiveDrainFromRadio(gatt: BluetoothGatt, fromRadioChar: BluetoothGattCharacteristic) {
        if (isDrainingFromRadio) return
        isDrainingFromRadio = true

        fun drainNext() {
            enqueueBleOperation(
                BleOperation.Read(fromRadioChar, { result: Result<ByteArray?> ->
                    result.onSuccess { data ->
                        if (data != null && data.isNotEmpty()) {
                            packetListener?.invoke(data)
                            // Add a 200ms delay before next read
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                                kotlinx.coroutines.delay(200)
                                drainNext()
                            }
                        } else {
                            isDrainingFromRadio = false
                        }
                    }.onFailure { error ->
                        isDrainingFromRadio = false
                    }
                })
            )
        }
        drainNext()
    }

    // BLE stack restart logic
    private fun restartBleStack() {
        Log.e("BluetoothDeviceManager", "Restarting BLE stack due to persistent error (status 257)")
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter
        if (adapter != null && adapter.isEnabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                adapter.disable()
                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        if (!adapter.isEnabled) {
                            adapter.enable()
                        } else {
                            Handler(Looper.getMainLooper()).postDelayed(this, 2500)
                        }
                    }
                }, 2500)
            } else {
                @Suppress("DEPRECATION")
                adapter.disable()
                Handler(Looper.getMainLooper()).postDelayed(object : Runnable {
                    override fun run() {
                        if (!adapter.isEnabled) {
                            @Suppress("DEPRECATION")
                            adapter.enable()
                        } else {
                            Handler(Looper.getMainLooper()).postDelayed(this, 2500)
                        }
                    }
                }, 2500)
            }
        }
    }

    // Public API for reliable write
    fun reliableWrite(characteristic: BluetoothGattCharacteristic, value: ByteArray, onResult: (Result<Boolean>) -> Unit) {
        enqueueBleOperation(BleOperation.ReliableWrite(characteristic, value, onResult))
    }

    /**
     * Subscribe to FROMNUM notifications and register the handler. Call this only after initial FROMRADIO drain and handshake are complete.
     */
    fun subscribeToFromNumNotifications() {
        val gatt = bluetoothGatt ?: return
        val service = gatt.getService(MESHTASTIC_SERVICE_UUID) ?: return
        val fromNumChar = service.getCharacteristic(FROMNUM_CHARACTERISTIC_UUID) ?: return
        gatt.setCharacteristicNotification(fromNumChar, true)
        val descriptor = fromNumChar.getDescriptor(configurationDescriptorUUID)
        descriptor?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(it)
            }
        }
        registerNotificationHandler(FROMNUM_CHARACTERISTIC_UUID) { _ ->
            Log.d("BluetoothDeviceManager", "FROMNUM notification received")
            val gatt = bluetoothGatt
            val service = gatt?.getService(MESHTASTIC_SERVICE_UUID)
            val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
            if (gatt != null && fromRadioChar != null) {
                Log.d("BluetoothDeviceManager", "FROMNUM notification triggering aggressiveDrainFromRadio")
                aggressiveDrainFromRadio(gatt, fromRadioChar)
            } else {
                Log.e("BluetoothDeviceManager", "FROMRADIO characteristic not found when handling FROMNUM notify!")
            }
        }
    }

    // Add a method to force cleanup
    fun cleanup() {
        resetReconnectState()
    }

    /**
     * Get the current status of the BLE operation queue
     * @return Triple containing (queue size, operation in progress, current operation type)
     */
    fun getQueueStatus(): Triple<Int, Boolean, String?> {
        val queueSize = bleQueue.size
        val inProgress = bleOperationInProgress
        val currentOpType = when (currentOperation) {
            is BleOperation.Write -> "Write"
            is BleOperation.Read -> "Read"
            is BleOperation.SetNotify -> "SetNotify"
            is BleOperation.ReliableWrite -> "ReliableWrite"
            null -> null
        }
        return Triple(queueSize, inProgress, currentOpType)
    }

    /**
     * Get the current reconnection status for debugging
     * @return Map containing reconnection state information
     */
    fun getReconnectionStatus(): Map<String, Any> {
        return mapOf(
            "reconnectAttempts" to reconnectAttempts,
            "pendingReconnect" to pendingReconnect,
            "isReconnectionAttempt" to isReconnectionAttempt,
            "reconnectStartTime" to reconnectStartTime,
            "maxReconnectTimeMs" to MAX_RECONNECT_TIME_MS,
            "maxReconnectAttempts" to MAX_RECONNECT_ATTEMPTS
        )
    }

    /**
     * Check if the system is in a clean state and ready for new connections
     * @return true if ready for new connections, false otherwise
     */
    fun isReadyForNewConnection(): Boolean {
        return bluetoothGatt == null && 
               !pendingReconnect && 
               reconnectAttempts == 0 && 
               bleQueue.isEmpty() && 
               !bleOperationInProgress &&
               _connectionState.value is ConnectionState.Disconnected
    }

    /**
     * Get a summary of the current connection state for debugging
     * @return String describing the current state
     */
    fun getConnectionStateSummary(): String {
        return "Connection State: ${_connectionState.value}, " +
               "GATT: ${if (bluetoothGatt != null) "Active" else "None"}, " +
               "Pending Reconnect: $pendingReconnect, " +
               "Reconnect Attempts: $reconnectAttempts, " +
               "Queue Size: ${bleQueue.size}, " +
               "Operation In Progress: $bleOperationInProgress"
    }

    /**
     * Get detailed OS-level Bluetooth connection information for debugging
     * @return String with detailed OS connection information
     */
    fun getOsConnectionInfo(): String {
        return try {
            val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val connectedDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
            val bondedDevices = bluetoothManager.adapter?.bondedDevices ?: emptySet()
            
            val osConnections = connectedDevices.map { device ->
                "Connected: ${device.name ?: "Unknown"} (${device.address}) - Bond State: ${device.bondState}"
            }
            
            val bondedDevicesInfo = bondedDevices.map { device ->
                "Bonded: ${device.name ?: "Unknown"} (${device.address}) - Bond State: ${device.bondState}"
            }
            
            "OS-Level Connections (${connectedDevices.size}):\n" +
            osConnections.joinToString("\n") + "\n\n" +
            "Bonded Devices (${bondedDevices.size}):\n" +
            bondedDevicesInfo.joinToString("\n")
        } catch (e: Exception) {
            "Error getting OS connection info: ${e.message}"
        }
    }

    // Add public getter for reconnection attempt status
    fun isReconnectionAttempt(): Boolean = isReconnectionAttempt
} 