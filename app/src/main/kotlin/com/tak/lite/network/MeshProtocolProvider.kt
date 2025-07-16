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

/**
 * Provides mesh protocol implementations with proper context usage.
 * 
 * Uses @ApplicationContext for all protocol operations to ensure
 * background compatibility and prevent context leaks.
 * 
 * ActivityContextProvider is only used for UI operations, not for
 * protocol or background operations.
 */
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
        Log.d(TAG, "=== MeshProtocolProvider Constructor ===")
        Log.d(TAG, "Initializing MeshProtocolProvider instance with initial protocol: ${_protocol.value.javaClass.simpleName}")
        Log.d(TAG, "Protocol StateFlow value: ${protocol.value.javaClass.simpleName}")
        Log.d(TAG, "Using @ApplicationContext for all protocol operations")
        Log.d(TAG, "=== MeshProtocolProvider Constructor Complete ===")
    }

    private fun createProtocol(type: String?): MeshProtocol {
        Log.d(TAG, "=== createProtocol ===")
        Log.d(TAG, "Creating protocol of type: $type")
        
        // Check if user is premium or in trial period
        val isPremium = billingManager.isPremium()
        val inTrial = billingManager.isInTrialPeriod()
        
        Log.d(TAG, "Premium status: $isPremium, in trial: $inTrial")
        
        if (!isPremium && !inTrial) {
            Log.d(TAG, "User is not premium and trial period has ended, returning disabled protocol")
            return DisabledMeshProtocol(context)
        }

        val protocol = when (type) {
            "Meshtastic" -> {
                Log.d(TAG, "Creating MeshtasticBluetoothProtocol with @ApplicationContext")
                MeshtasticBluetoothProtocol(bluetoothDeviceManager, context)
            }
            "MeshtasticAidl" -> {
                Log.d(TAG, "Creating MeshtasticAidlProtocol with @ApplicationContext")
                MeshtasticAidlProtocol(context, activityContextProvider)
            }
            else -> {
                Log.d(TAG, "Creating Layer2MeshNetworkProtocol with @ApplicationContext")
                Layer2MeshNetworkProtocol(context)
            }
        }
        
        Log.d(TAG, "Created protocol: ${protocol.javaClass.simpleName}")
        Log.d(TAG, "=== createProtocol Complete ===")
        return protocol
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
        Log.d(TAG, "=== Manually updating protocol type ===")
        Log.d(TAG, "New type: $type")
        val oldProtocol = _protocol.value
        val oldProtocolName = oldProtocol.javaClass.simpleName
        Log.d(TAG, "Old protocol: $oldProtocolName (instance: ${oldProtocol.hashCode()})")
        
        // Clean up the old protocol before creating a new one
        Log.d(TAG, "Cleaning up old protocol: $oldProtocolName")
        try {
            // Disconnect and clean up the old protocol
            oldProtocol.disconnectFromDevice()
            
            // Additional cleanup for AIDL protocol
            if (oldProtocol is MeshtasticAidlProtocol) {
                oldProtocol.cleanup()
            }
            
            Log.d(TAG, "Successfully cleaned up old protocol: $oldProtocolName")
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old protocol $oldProtocolName", e)
        }
        
        // Create the new protocol
        Log.d(TAG, "Creating new protocol of type: $type")
        val newProtocol = createProtocol(type)
        Log.d(TAG, "New protocol created: ${newProtocol.javaClass.simpleName} (instance: ${newProtocol.hashCode()})")
        _protocol.value = newProtocol
        Log.d(TAG, "Protocol updated successfully")
        Log.d(TAG, "=== Protocol type update complete ===")
    }
} 