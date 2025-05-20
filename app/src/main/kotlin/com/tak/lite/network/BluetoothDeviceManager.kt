package com.tak.lite.network

import android.bluetooth.*
import android.bluetooth.le.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.*

class BluetoothDeviceManager(private val context: Context) {
    sealed class ConnectionState {
        object Disconnected : ConnectionState()
        object Connecting : ConnectionState()
        data class Connected(val device: BluetoothDevice) : ConnectionState()
        data class Failed(val reason: String) : ConnectionState()
    }

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

    fun scanForDevices(serviceUuid: UUID, onResult: (BluetoothDevice) -> Unit, onScanFinished: () -> Unit) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
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
        _connectionState.value = ConnectionState.Connecting
        gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    bluetoothGatt = gatt
                    _connectionState.value = ConnectionState.Connected(device)
                    gatt.discoverServices()
                    CoroutineScope(Dispatchers.Main).launch { onConnected(true) }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    bluetoothGatt = null
                    _connectionState.value = ConnectionState.Disconnected
                    CoroutineScope(Dispatchers.Main).launch { onConnected(false) }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val service = gatt.getService(MESHTASTIC_SERVICE_UUID)
                    val fromRadioChar = service?.getCharacteristic(FROMRADIO_CHARACTERISTIC_UUID)
                    if (fromRadioChar != null) {
                        gatt.setCharacteristicNotification(fromRadioChar, true)
                        // Set up descriptor for notifications
                        val descriptor = fromRadioChar.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        descriptor?.let {
                            it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(it)
                        }
                    }
                }
            }

            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (characteristic.uuid == FROMRADIO_CHARACTERISTIC_UUID) {
                    val data = characteristic.value
                    packetListener?.invoke(data)
                }
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                if (characteristic.uuid == FROMRADIO_CHARACTERISTIC_UUID && status == BluetoothGatt.GATT_SUCCESS) {
                    val data = characteristic.value
                    packetListener?.invoke(data)
                }
            }
        }
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            device.connectGatt(context, true, gattCallback!!)
        } else {
            val bondReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    val bondedDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (action == BluetoothDevice.ACTION_BOND_STATE_CHANGED && bondedDevice?.address == device.address) {
                        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                        if (bondState == BluetoothDevice.BOND_BONDED) {
                            context.unregisterReceiver(this)
                            device.connectGatt(context, true, gattCallback!!)
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
        bluetoothGatt?.close()
        bluetoothGatt = null
        _connectionState.value = ConnectionState.Disconnected
    }
} 