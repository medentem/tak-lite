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
    private var reconnectAttempts = 0
    private var pendingReconnect: Boolean = false
    private var lastDevice: BluetoothDevice? = null
    private var lastOnConnected: ((Boolean) -> Unit)? = null
    private var mtuRetry = false
    private var skipRefresh: Boolean = false
    private var initialDrainDone = false

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
        when (op) {
            is BleOperation.Write -> op.onResult(Result.failure<Boolean>(Exception("Operation timed out")))
            is BleOperation.Read -> op.onResult(Result.failure<ByteArray?>(Exception("Operation timed out")))
            is BleOperation.SetNotify -> op.onResult(Result.failure<Boolean>(Exception("Operation timed out")))
            is BleOperation.ReliableWrite -> op.onResult(Result.failure<Boolean>(Exception("Operation timed out")))
            null -> {}
        }
        completeCurrentOperation(Result.failure<Any?>(Exception("Operation timed out")))
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
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetoothManager.adapter ?: return
        val bleScanner = adapter.bluetoothLeScanner
        val scanFilter = ScanFilter.Builder().setServiceUuid(ParcelUuid(serviceUuid)).build()
        val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        val discovered = mutableSetOf<String>()

        val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                if (device.address !in discovered) {
                    discovered.add(device.address)
                    onResult(device)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e("BluetoothDeviceManager", "BLE scan failed: $errorCode")
                onScanFinished()
            }
        }
        bleScanner.startScan(listOf(scanFilter), scanSettings, scanCallback)
        CoroutineScope(Dispatchers.Main).launch {
            kotlinx.coroutines.delay(4000)
            bleScanner.stopScan(scanCallback)
            onScanFinished()
        }
    }

    fun connect(device: BluetoothDevice, onConnected: (Boolean) -> Unit) {
        lastDevice = device
        lastOnConnected = onConnected
        reconnectAttempts = 0
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
                    when (status) {
                        133 -> {
                            Log.e("BluetoothDeviceManager", "GATT_ERROR (133) encountered. Closing GATT and retrying with autoConnect=true.")
                            gatt.close()
                            // Retry with autoConnect=true
                            CoroutineScope(Dispatchers.Main).launch {
                                delay(1000)
                                connectInternal(device, onConnected)
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
                            scheduleReconnect("Disconnected")
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
                    delay(1000) // Wait 1s after service discovery
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val service = gatt.getService(MESHTASTIC_SERVICE_UUID)
                        val fromNumChar = service?.getCharacteristic(FROMNUM_CHARACTERISTIC_UUID)
                        val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
                        if (fromNumChar != null && fromRadioChar != null) {
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
                            if (fromNumChar == null) Log.e("BluetoothDeviceManager", "FROMNUM characteristic not found!")
                            if (fromRadioChar == null) Log.e("BluetoothDeviceManager", "FROMRADIO characteristic not found!")
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
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            device.connectGatt(context, gattCallback!!)
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
                            device.connectGatt(context, gattCallback!!)
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
        bluetoothGatt?.disconnect()
        // Do not close or set state here; let onConnectionStateChange handle it
    }

    // Robust reconnect logic
    private fun scheduleReconnect(reason: String) {
        // Only allow one pending reconnect, and limit attempts
        if (pendingReconnect || reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e("BluetoothDeviceManager", "Reconnect skipped: $reason (attempts=$reconnectAttempts)")
            return
        }
        pendingReconnect = true
        reconnectAttempts++
        val delayMs = (1000L * reconnectAttempts).coerceAtMost(10000L)
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

    // Aggressive drain logic and isDrainingFromRadio flag
    @Volatile private var isDrainingFromRadio = false
    @Volatile private var isStackSettling = false
    public fun aggressiveDrainFromRadio(gatt: BluetoothGatt, fromRadioChar: BluetoothGattCharacteristic) {
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
} 