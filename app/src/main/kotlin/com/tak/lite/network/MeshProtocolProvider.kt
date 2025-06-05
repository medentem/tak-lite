package com.tak.lite.network

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tak.lite.di.MeshProtocol
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshProtocolProvider @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val bluetoothDeviceManager = BluetoothDeviceManager(context)
    private val _protocol = MutableStateFlow(createProtocol(prefs.getString("mesh_network_type", "Layer 2")))
    val protocol: StateFlow<MeshProtocol> = _protocol.asStateFlow()

    init {
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "mesh_network_type") {
                _protocol.value = createProtocol(prefs.getString("mesh_network_type", "Layer 2"))
            }
        }
    }

    private fun createProtocol(type: String?): MeshProtocol {
        return if (type == "Meshtastic") {
            MeshtasticBluetoothProtocol(bluetoothDeviceManager, context)
        } else {
            Layer2MeshNetworkProtocol(context)
        }
    }

    fun getBluetoothDeviceManager(): BluetoothDeviceManager = bluetoothDeviceManager
} 