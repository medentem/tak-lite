package com.tak.lite.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.tak.lite.di.MeshProtocol
import com.tak.lite.util.BillingManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MeshProtocolProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val billingManager: BillingManager,
    private val activityContextProvider: com.tak.lite.di.ActivityContextProvider
) {
    private val TAG = "MeshProtocolProvider"
    private val prefs: SharedPreferences = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    private val bluetoothDeviceManager = BluetoothDeviceManager(context)
    private val _protocol = MutableStateFlow(createProtocol(prefs.getString("mesh_network_type", "Meshtastic")))
    val protocol: StateFlow<MeshProtocol> = _protocol.asStateFlow()

    init {
        Log.d(TAG, "Initializing MeshProtocolProvider instance with initial protocol: ${_protocol.value.javaClass.simpleName}")
    }

    private fun createProtocol(type: String?): MeshProtocol {
        // Check if user is premium or in trial period
        val isPremium = billingManager.isPremium()
        val inTrial = billingManager.isInTrialPeriod()
        
        if (!isPremium && !inTrial) {
            Log.d(TAG, "User is not premium and trial period has ended, returning disabled protocol")
            return DisabledMeshProtocol(context)
        }

        return when (type) {
            "Meshtastic" -> {
                Log.d(TAG, "Creating MeshtasticBluetoothProtocol")
                MeshtasticBluetoothProtocol(bluetoothDeviceManager, context)
            }
            "MeshtasticAidl" -> {
                Log.d(TAG, "Creating MeshtasticAidlProtocol")
                MeshtasticAidlProtocol(context, activityContextProvider)
            }
            else -> {
                Log.d(TAG, "Creating Layer2MeshNetworkProtocol")
                Layer2MeshNetworkProtocol(context)
            }
        }
    }
    
    fun cleanup() {
        try {
            val currentProtocol = _protocol.value
            if (currentProtocol is MeshtasticAidlProtocol) {
                currentProtocol.cleanup()
            }
            Log.d(TAG, "Cleaned up protocol provider")
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
    
    fun updateProtocolType(type: String) {
        Log.d(TAG, "Manually updating protocol type to: $type")
        val oldProtocol = _protocol.value.javaClass.simpleName
        _protocol.value = createProtocol(type)
        Log.d(TAG, "Manually created new protocol: ${_protocol.value.javaClass.simpleName} (was: $oldProtocol)")
    }
} 