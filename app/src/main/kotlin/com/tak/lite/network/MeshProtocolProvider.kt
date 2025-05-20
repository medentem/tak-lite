package com.tak.lite.network

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tak.lite.di.Layer2MeshProtocolAdapter
import com.tak.lite.di.MeshProtocol
import com.tak.lite.di.MeshtasticBluetoothProtocolAdapter

object MeshProtocolProvider {
    private lateinit var prefs: SharedPreferences
    private lateinit var _protocol: MutableStateFlow<MeshProtocol>
    val protocol: StateFlow<MeshProtocol> get() {
        check(:: _protocol.isInitialized) { "MeshProtocolProvider must be initialized before use" }
        return _protocol.asStateFlow()
    }

    private var bluetoothDeviceManager: BluetoothDeviceManager? = null
    fun getBluetoothDeviceManager(): BluetoothDeviceManager? = bluetoothDeviceManager

    fun initialize(context: Context) {
        prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        bluetoothDeviceManager = BluetoothDeviceManager(context)
        _protocol = MutableStateFlow(createProtocol(context, prefs.getString("mesh_network_type", "Layer 2")))
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "mesh_network_type") {
                _protocol.value = createProtocol(context, prefs.getString("mesh_network_type", "Layer 2"))
            }
        }
    }

    private fun createProtocol(context: Context, type: String?): MeshProtocol {
        return if (type == "Meshtastic") {
            val manager = bluetoothDeviceManager ?: BluetoothDeviceManager(context)
            MeshtasticBluetoothProtocolAdapter(MeshtasticBluetoothProtocol(manager, context))
        } else {
            Layer2MeshProtocolAdapter(MeshNetworkProtocol(context))
        }
    }

    fun getProtocol(): MeshProtocol {
        check(:: _protocol.isInitialized) { "MeshProtocolProvider must be initialized before use" }
        return _protocol.value
    }
} 