package com.tak.lite.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.tak.lite.di.MeshProtocol
import com.tak.lite.util.BillingManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshProtocolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val billingManager: BillingManager
) {
    private val TAG = "MeshProtocolProvider"
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val bluetoothDeviceManager = BluetoothDeviceManager(context)
    private val _protocol = MutableStateFlow(createProtocol(prefs.getString("mesh_network_type", "Meshtastic")))
    val protocol: StateFlow<MeshProtocol> = _protocol.asStateFlow()

    init {
        Log.d(TAG, "Initializing MeshProtocolProvider with initial protocol: ${_protocol.value.javaClass.simpleName}")
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "mesh_network_type") {
                val newType = prefs.getString("mesh_network_type", "Meshtastic")
                Log.d(TAG, "Mesh network type changed to: $newType")
                _protocol.value = createProtocol(newType)
                Log.d(TAG, "Created new protocol: ${_protocol.value.javaClass.simpleName}")
            }
        }
    }

    private fun createProtocol(type: String?): MeshProtocol {
        // Check if user is premium or in trial period
        val isPremium = billingManager.isPremium()
        val inTrial = billingManager.isInTrialPeriod()
        
        if (!isPremium && !inTrial) {
            Log.d(TAG, "User is not premium and trial period has ended, returning disabled protocol")
            return DisabledMeshProtocol(context)
        }

        return if (type == "Meshtastic") {
            Log.d(TAG, "Creating MeshtasticBluetoothProtocol")
            MeshtasticBluetoothProtocol(bluetoothDeviceManager, context)
        } else {
            Log.d(TAG, "Creating Layer2MeshNetworkProtocol")
            Layer2MeshNetworkProtocol(context)
        }
    }

    fun getBluetoothDeviceManager(): BluetoothDeviceManager = bluetoothDeviceManager
} 