package com.tak.lite.network

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import android.util.Log
import com.geeksville.mesh.AppOnlyProtos
import com.geeksville.mesh.ChannelProtos
import com.geeksville.mesh.DataPacket
import com.geeksville.mesh.IMeshService
import com.geeksville.mesh.MeshProtos
import com.geeksville.mesh.MeshProtos.MeshPacket
import com.geeksville.mesh.MessageStatus
import com.geeksville.mesh.MyNodeInfo
import com.geeksville.mesh.NodeInfo
import com.geeksville.mesh.Portnums
import com.google.protobuf.ByteString
import com.tak.lite.di.ActivityContextProvider
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import com.tak.lite.data.model.MessageStatus as BaseMessageStatus


/**
 * AIDL-based mesh protocol implementation.
 * 
 * Uses @ApplicationContext for all background operations to ensure:
 * - Reliable background operation
 * - No context leaks
 * - Consistent broadcast receiver registration/unregistration
 * 
 * ActivityContextProvider is only used for UI operations, not for
 * protocol or background operations.
 */
class MeshtasticAidlProtocol @Inject constructor(
    @ApplicationContext private val context: Context,
    private val activityContextProvider: ActivityContextProvider,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) : MeshtasticBaseProtocol(context, coroutineContext) {
    private val TAG = "MeshtasticAidlProtocol"
    
    // AIDL Service binding
    private var meshService: IMeshService? = null
    private var isMeshServiceBound = false
    private var userInitiatedDisconnect = false // Track user-initiated disconnects
    
    // Track broadcast receiver registration - ALWAYS use application context
    private var isReceiverRegistered = false
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(TAG, "=== Service Connection Debug ===")
            Log.d(TAG, "ComponentName: $name")
            Log.d(TAG, "Service IBinder null: ${service == null}")
            Log.d(TAG, "Service IBinder class: ${service?.javaClass?.name}")
            
            try {
                meshService = IMeshService.Stub.asInterface(service)
                Log.d(TAG, "Successfully created IMeshService interface")
                Log.d(TAG, "MeshService interface class: ${meshService?.javaClass?.name}")
                
                // Test if the service is responsive
                try {
                    val connectionState = meshService?.connectionState()
                    Log.d(TAG, "Service connection state test: $connectionState")
                } catch (e: Exception) {
                    Log.e(TAG, "Service connection state test failed: ${e.message}")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create IMeshService interface: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                return
            }
            
            isMeshServiceBound = true
            Log.i(TAG, "Connected to Meshtastic AIDL Service")

            // Ensure broadcast receiver remains registered after bind
            if (!isReceiverRegistered) {
                Log.d(TAG, "Receiver not registered after onServiceConnected, registering")
                registerMeshReceiver()
            }
            // Set to Connecting initially - we'll update to Connected only after successful handshake
            _connectionState.value = MeshConnectionState.Connecting
            
            // Start AIDL handshake to get initial data
            CoroutineScope(coroutineContext).launch {
                performAidlHandshake()
            }
            
            // Start providing location if needed
            if (requiresAppLocationSend) {
                meshService?.startProvideLocation()
            }
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            meshService = null
            isMeshServiceBound = false
            Log.w(TAG, "Disconnected from Meshtastic AIDL Service")
            Log.w(TAG, "ComponentName: $name")
            Log.w(TAG, "User initiated disconnect: $userInitiatedDisconnect")
            _connectionState.value = MeshConnectionState.Disconnected
            
            // Ensure receiver stays registered during transient disconnects
            if (!isReceiverRegistered) {
                Log.d(TAG, "Receiver was unregistered during disconnect; re-registering")
                registerMeshReceiver()
            }

            // Only attempt to rebind if this wasn't a user-initiated disconnect
            if (!userInitiatedDisconnect) {
                Log.d(TAG, "Service disconnected unexpectedly, attempting to rebind...")
                CoroutineScope(coroutineContext).launch {
                    // Use exponential backoff for rebinding attempts
                    var retryCount = 0
                    val maxRetries = 30 // make rebind persistent for minutes if needed
                    val baseDelay = 2000L
                    
                    while (retryCount < maxRetries && !userInitiatedDisconnect && !isMeshServiceBound) {
                        val delay = baseDelay * (1 shl retryCount) // Exponential backoff: 2s, 4s, 8s, 16s, 32s
                        Log.d(TAG, "Rebinding attempt ${retryCount + 1}/$maxRetries after ${delay}ms delay")
                        kotlinx.coroutines.delay(delay)
                        
                        if (!userInitiatedDisconnect) {
                            val success = bindMeshService()
                            if (success) {
                                Log.i(TAG, "Successfully rebound to Meshtastic AIDL Service")
                                break
                            } else {
                                Log.w(TAG, "Rebinding attempt ${retryCount + 1} failed")
                            }
                        }
                        retryCount++
                    }
                    
                    if (retryCount >= maxRetries && !isMeshServiceBound) {
                        Log.e(TAG, "Failed to rebind after $maxRetries attempts")
                        _connectionState.value = MeshConnectionState.Error("Failed to reconnect to Meshtastic service after multiple attempts")
                    }
                }
            } else {
                Log.d(TAG, "Service disconnected due to user request, not rebinding")
            }
        }
    }
    
    // Broadcast receiver for mesh events
    private val meshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "=== BroadcastReceiver.onReceive called ===")
            Log.d(TAG, "Instance ID: ${this@MeshtasticAidlProtocol.hashCode()}")
            Log.d(TAG, "Context: ${context?.javaClass?.name}")
            Log.d(TAG, "Intent: ${intent?.action}")
            Log.d(TAG, "Intent extras: ${intent?.extras?.keySet()}")
            Log.d(TAG, "Thread: ${Thread.currentThread().name}")
            Log.d(TAG, "Process ID: ${android.os.Process.myPid()}")
            handleMeshBroadcast(intent)
        }
    }
    
    init {
        Log.d(TAG, "=== Initializing MeshtasticAidlProtocol ===")
        Log.d(TAG, "Instance ID: ${this.hashCode()}")
        Log.d(TAG, "Using application context for all background operations")
        
        // Register broadcast receiver using application context (best practice for background operations)
        registerMeshReceiver()
        
        Log.d(TAG, "Attempting to bind to mesh service...")
        while (!bindMeshService()) {
            try {
                // Wait for the service to bind
                Thread.sleep(1000)
            } catch (e: InterruptedException) {
                Log.e(TAG, "Binding interrupted", e)
                break
            }
        }
        
        // Test broadcast receiver registration with a test broadcast
        CoroutineScope(coroutineContext).launch {
            kotlinx.coroutines.delay(2000) // Wait 2 seconds
            Log.d(TAG, "Testing broadcast receiver registration...")
            val testIntent = Intent("com.geeksville.mesh.TEST_BROADCAST")
            Log.d(TAG, "Sending test broadcast with action: ${testIntent.action}")
            
            // Always use application context for sending broadcasts (consistent with registration)
            context.sendBroadcast(testIntent)
            Log.d(TAG, "Test broadcast sent using application context")
        }
        
        Log.d(TAG, "=== MeshtasticAidlProtocol initialization complete ===")
    }
    
    private fun bindMeshService(): Boolean {
        return try {
            Log.i(TAG, "Attempting to bind to Meshtastic AIDL Service...")
            
            // Check if Meshtastic app is installed
            val packageManager = context.packageManager
            val meshtasticPackageInfo = try {
                packageManager.getPackageInfo("com.geeksville.mesh", 0)
            } catch (e: Exception) {
                Log.e(TAG, "Meshtastic app not found: ${e.message}")
                Log.e(TAG, "Exception type: ${e.javaClass.simpleName}")
                if (e is android.content.pm.PackageManager.NameNotFoundException) {
                    _connectionState.value = MeshConnectionState.Error("Meshtastic app not installed or not accessible")
                } else {
                    _connectionState.value = MeshConnectionState.Error("Error checking Meshtastic app: ${e.message}")
                }
                return false
            }

            val appInfo = packageManager.getApplicationInfo("com.geeksville.mesh", 0)
            val appLabel = packageManager.getApplicationLabel(appInfo)
            Log.d(TAG, "Meshtastic app found: $appLabel (version: ${meshtasticPackageInfo.versionName})")
            Log.d(TAG, "App version code: ${meshtasticPackageInfo.versionCode}")
            Log.d(TAG, "App target SDK: ${appInfo.targetSdkVersion}")

            Log.i(TAG, "Attempting to bind to Mesh Service...")

            // Ensure our broadcast receiver is registered before binding (idempotent)
            if (!isReceiverRegistered) {
                Log.d(TAG, "Receiver not registered prior to bind, registering now")
                registerMeshReceiver()
            }
            val intent = Intent("com.geeksville.mesh.Service")
            intent.setClassName("com.geeksville.mesh", "com.geeksville.mesh.service.MeshService")
            
            // Check if the service exists
            val resolveInfo = packageManager.resolveService(intent, 0)
            if (resolveInfo == null) {
                Log.e(TAG, "Meshtastic service not found in package")
                _connectionState.value = MeshConnectionState.Error("Meshtastic service not found")
                return false
            }
            
            Log.d(TAG, "Found Meshtastic service: ${resolveInfo.serviceInfo.name}")
            Log.d(TAG, "Service exported: ${resolveInfo.serviceInfo.exported}")
            Log.d(TAG, "Service permission: ${resolveInfo.serviceInfo.permission}")
            
            // Check if we have the required permission
            val hasPermission = context.checkSelfPermission("com.geeksville.mesh.permission.BIND_MESH_SERVICE") == android.content.pm.PackageManager.PERMISSION_GRANTED
            Log.d(TAG, "Has BIND_MESH_SERVICE permission: $hasPermission")
            
            // Check if Meshtastic app is running
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningProcesses = activityManager.runningAppProcesses
            val meshtasticRunning = runningProcesses?.any { it.processName == "com.geeksville.mesh" } ?: false
            Log.d(TAG, "Meshtastic app running: $meshtasticRunning")
            
            // Use stronger binding flags for background operation
            // BIND_IMPORTANT ensures the binding is maintained even when the app is backgrounded
            // BIND_AUTO_CREATE allows the service to be created if it's not running
            val bindingFlags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT
            Log.d(TAG, "Binding with flags: BIND_AUTO_CREATE | BIND_IMPORTANT")
            val bound = context.bindService(intent, serviceConnection, bindingFlags)
            if (bound) {
                Log.i(TAG, "Successfully bound to Meshtastic AIDL Service")
            } else {
                Log.e(TAG, "Failed to bind to Meshtastic AIDL Service - bindService returned false")
                _connectionState.value = MeshConnectionState.Error("Failed to bind to Meshtastic service - service may not be running")
            }
            bound
        } catch (e: Exception) {
            Log.e(TAG, "Exception binding to Meshtastic AIDL Service", e)
            _connectionState.value = MeshConnectionState.Error("Exception binding to service: ${e.message}")
            false
        }
    }
    
    private fun unbindMeshService() {
        Log.d(TAG, "unbindMeshService called, isMeshServiceBound: $isMeshServiceBound")
        if (isMeshServiceBound) {
            try {
                context.unbindService(serviceConnection)
                Log.i(TAG, "Successfully unbound from Meshtastic AIDL Service")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "MeshService not registered or already unbound: ${e.message}")
            }
            isMeshServiceBound = false
            meshService = null
        } else {
            Log.d(TAG, "Service was not bound, nothing to unbind")
        }
    }
    
    private fun registerMeshReceiver() {
        // Prevent duplicate registration
        if (isReceiverRegistered) {
            Log.d(TAG, "Broadcast receiver already registered, skipping registration")
            return
        }
        
        val filter = IntentFilter().apply {
            addAction("com.geeksville.mesh.NODE_CHANGE")
            addAction("com.geeksville.mesh.RECEIVED.NODEINFO_APP")
            addAction("com.geeksville.mesh.RECEIVED.POSITION_APP")
            addAction("com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP")
            addAction("com.geeksville.mesh.RECEIVED.ATAK_PLUGIN")
            addAction("com.geeksville.mesh.RECEIVED.ROUTING_APP")
            addAction("com.geeksville.mesh.MESH_CONNECTED")
            addAction("com.geeksville.mesh.MESH_DISCONNECTED")
            addAction("com.geeksville.mesh.MESSAGE_STATUS")
            addAction("com.geeksville.mesh.TEST_BROADCAST") // Add test broadcast action
        }
        
        // ALWAYS use application context for background broadcast receivers (best practice)
        try {
            Log.d(TAG, "=== Broadcast Receiver Registration ===")
            Log.d(TAG, "Context type: ${context.javaClass.name}")
            Log.d(TAG, "Context package: ${context.packageName}")
            Log.d(TAG, "Using application context for background mesh operations")
            Log.d(TAG, "Filter actions: ${filter.actionsIterator().asSequence().toList()}")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // On API 33+, specify exported to allow cross-app broadcasts from Meshtastic
                try {
                    context.registerReceiver(meshReceiver, filter, Context.RECEIVER_EXPORTED)
                    Log.d(TAG, "Registered receiver with RECEIVER_EXPORTED (API33+)")
                } catch (e: Exception) {
                    Log.w(TAG, "API33+ exported registration failed: ${e.message}. Trying NOT_EXPORTED as fallback (may not receive cross-app).")
                    context.registerReceiver(meshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                }
            } else {
                // On older APIs, use legacy signature; cross-app broadcasts are allowed by default
                context.registerReceiver(meshReceiver, filter)
                Log.d(TAG, "Registered receiver using legacy API (<33)")
            }
            
            // Track successful registration
            isReceiverRegistered = true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register mesh receiver", e)
            // Reset tracking on failure
            isReceiverRegistered = false
        }
    }
    
    private fun unregisterMeshReceiver() {
        if (!isReceiverRegistered) {
            Log.d(TAG, "Broadcast receiver not registered, skipping unregistration")
            return
        }
        
        try {
            // Always use application context for unregistration (same as registration)
            context.unregisterReceiver(meshReceiver)
            Log.d(TAG, "Successfully unregistered mesh broadcast receiver using application context")
            
            // Reset tracking state
            isReceiverRegistered = false
            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister mesh receiver", e)
            // Reset tracking even on failure to prevent stuck state
            isReceiverRegistered = false
        }
    }
    
    private fun handleMeshBroadcast(intent: Intent?) {
        if (intent?.action == null) {
            Log.w(TAG, "Received null intent or action")
            return
        }
        
        Log.d(TAG, "Received broadcast: ${intent.action}")
        
        when (intent.action) {
            "com.geeksville.mesh.NODE_CHANGE" -> {
                val nodeInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.NodeInfo", NodeInfo::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.NodeInfo")
                }
                handleNodeChange(nodeInfo)
            }
            "com.geeksville.mesh.RECEIVED.NODEINFO_APP" -> {
                Log.d(TAG, "=== NODEINFO_APP Broadcast Debug ===")
                Log.d(TAG, "Intent extras keys: ${intent.extras?.keySet()}")
                intent.extras?.keySet()?.forEach { key ->
                    val value = intent.extras?.get(key)
                    Log.d(TAG, "Extra key: $key, value: $value, type: ${value?.javaClass?.name}")
                }
                
                val dataPacket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Payload", DataPacket::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Payload")
                }
                Log.d(TAG, "Extracted DataPacket: $dataPacket")
                handleNodeInfoPacket(dataPacket)
            }
            "com.geeksville.mesh.RECEIVED.POSITION_APP" -> {
                Log.d(TAG, "=== POSITION_APP Broadcast Debug ===")
                Log.d(TAG, "Intent extras keys: ${intent.extras?.keySet()}")
                intent.extras?.keySet()?.forEach { key ->
                    val value = intent.extras?.get(key)
                    Log.d(TAG, "Extra key: $key, value: $value, type: ${value?.javaClass?.name}")
                }
                
                val dataPacket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Payload", DataPacket::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Payload")
                }
                Log.d(TAG, "Extracted DataPacket: $dataPacket")
                handlePositionPacket(dataPacket)
            }
            "com.geeksville.mesh.RECEIVED.TEXT_MESSAGE_APP" -> {
                val dataPacket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Payload", DataPacket::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Payload")
                }
                handleTextMessage(dataPacket)
            }
            "com.geeksville.mesh.RECEIVED.ATAK_PLUGIN" -> {
                val dataPacket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Payload", DataPacket::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Payload")
                }
                handleAnnotation(dataPacket)
            }
            "com.geeksville.mesh.RECEIVED.ROUTING_APP" -> {
                val dataPacket = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Payload", DataPacket::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Payload")
                }
                handleRoutingApp(dataPacket)
            }
            "com.geeksville.mesh.MESH_CONNECTED" -> {
                Log.i(TAG, "Received MESH_CONNECTED broadcast - Meshtastic app connected to mesh device")
                _connectionState.value = MeshConnectionState.Connected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
            }
            "com.geeksville.mesh.MESH_DISCONNECTED" -> {
                Log.i(TAG, "Received MESH_DISCONNECTED broadcast - Meshtastic app disconnected from mesh device")
                _connectionState.value = MeshConnectionState.Disconnected
            }
            "com.geeksville.mesh.MESSAGE_STATUS" -> {
                val packetId = intent.getIntExtra("com.geeksville.mesh.PacketId", 0)
                val status = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra("com.geeksville.mesh.Status", MessageStatus::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra("com.geeksville.mesh.Status")
                }
                handleMessageStatus(packetId, status)
            }
            "com.geeksville.mesh.TEST_BROADCAST" -> {
                Log.d(TAG, "Test broadcast received - broadcast receiver is working!")
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }

    private suspend fun performAidlHandshake() {
        Log.i(TAG, "Starting AIDL handshake...")
        _configDownloadStep.value = ConfigDownloadStep.NotStarted
        
        try {
            // Step 1: Get MyNodeInfo (equivalent to MY_INFO in Bluetooth)
            Log.d(TAG, "Step 1: Getting MyNodeInfo...")
            _configDownloadStep.value = ConfigDownloadStep.DownloadingMyInfo
            updateConfigStepCounter(ConfigDownloadStep.DownloadingMyInfo)
            
            // Add comprehensive logging for debugging the binder issue
            Log.d(TAG, "=== AIDL Handshake Debug Info ===")
            Log.d(TAG, "Service bound: $isMeshServiceBound")
            Log.d(TAG, "MeshService null: ${meshService == null}")
            Log.d(TAG, "Connection state: ${_connectionState.value}")
            
            // Test basic service connectivity first
            try {
                val connectionState = meshService?.connectionState()
                Log.d(TAG, "Meshtastic app connection state: $connectionState")
                if (connectionState == "DISCONNECTED") {
                    Log.w(TAG, "Meshtastic app is not connected to any mesh device")
                    // Early return - no point in continuing if app is disconnected
                    _connectionState.value = MeshConnectionState.ServiceConnected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
                    Log.i(TAG, "Setting connection state to ServiceConnected - AIDL service connected but no mesh device attached")
                    return
                } else if (connectionState == "CONNECTED") {
                    Log.i(TAG, "Meshtastic app is connected to a mesh device")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get connection state: ${e.javaClass.simpleName}: ${e.message}")
            }
            
            // Test getting my ID first (simpler call)
            try {
                val myId = meshService?.getMyId()
                Log.d(TAG, "My ID from service: $myId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get my ID: ${e.javaClass.simpleName}: ${e.message}")
            }
            
            // Now try the problematic call with detailed error handling
            Log.d(TAG, "Attempting to call getMyNodeInfo()...")
            val myNodeInfo = try {
                meshService?.getMyNodeInfo()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException calling getMyNodeInfo: ${e.message}")
                Log.e(TAG, "This suggests an AIDL interface mismatch or service binding issue")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception calling getMyNodeInfo: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                null
            }
            
            // Add comprehensive logging for MyNodeInfo processing
            Log.d(TAG, "=== MyNodeInfo Processing Debug ===")
            Log.d(TAG, "MyNodeInfo raw result: $myNodeInfo")
            Log.d(TAG, "MyNodeInfo class: ${myNodeInfo?.javaClass?.name}")
            
            if (myNodeInfo != null) {
                // Log MyNodeInfo details before conversion
                Log.d(TAG, "MyNodeInfo details:")
                Log.d(TAG, "  - myNodeNum (signed): ${myNodeInfo.myNodeNum}")
                Log.d(TAG, "  - myNodeNum (hex): 0x${myNodeInfo.myNodeNum.toString(16)}")
                Log.d(TAG, "  - myNodeNum (unsigned): ${(myNodeInfo.myNodeNum.toLong() and 0xFFFFFFFFL)}")
                Log.d(TAG, "  - minAppVersion: ${myNodeInfo.minAppVersion}")
                Log.d(TAG, "  - hasGPS: ${myNodeInfo.hasGPS}")
                Log.d(TAG, "  - model: ${myNodeInfo.model}")
                Log.d(TAG, "  - firmwareVersion: ${myNodeInfo.firmwareVersion}")
                Log.d(TAG, "  - deviceId: ${myNodeInfo.deviceId}")
                
                // Convert to protobuf format and use base class handler
                Log.d(TAG, "Converting MyNodeInfo to MeshProtos.MyNodeInfo...")
                val meshProtosMyNodeInfo = try {
                    myNodeInfo.toMeshProtosMyNodeInfo()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to convert MyNodeInfo to MeshProtos.MyNodeInfo: ${e.message}", e)
                    null
                }
                
                if (meshProtosMyNodeInfo != null) {
                    Log.d(TAG, "Conversion successful, MeshProtos.MyNodeInfo details:")
                    Log.d(TAG, "  - myNodeNum: ${meshProtosMyNodeInfo.myNodeNum}")
                    Log.d(TAG, "  - minAppVersion: ${meshProtosMyNodeInfo.minAppVersion}")
                    
                    Log.d(TAG, "Calling base class handleMyInfo()...")
                    try {
                        handleMyInfo(meshProtosMyNodeInfo)
                        Log.d(TAG, "Successfully processed MyNodeInfo using base class handler")
                        // Set connection state to Connected since we have valid mesh device connection
                        _connectionState.value = MeshConnectionState.Connected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
                        Log.i(TAG, "Mesh device connection confirmed - connection state set to Connected")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to process MyNodeInfo in base class handler: ${e.message}", e)
                        // Failed to process MyNodeInfo - set connection state to Disconnected
                        _connectionState.value = MeshConnectionState.Disconnected
                        Log.i(TAG, "Failed to process MyNodeInfo - setting connection state to Disconnected")
                        return
                    }
                } else {
                    Log.e(TAG, "Failed to convert MyNodeInfo to MeshProtos.MyNodeInfo")
                    // Failed to convert MyNodeInfo - set connection state to Disconnected
                    _connectionState.value = MeshConnectionState.Disconnected
                    Log.i(TAG, "Failed to convert MyNodeInfo - setting connection state to Disconnected")
                    return
                }
            } else {
                Log.w(TAG, "MyNodeInfo is null - Meshtastic app is not connected to a mesh device")
                // Try to get node ID from getMyId() as fallback
                try {
                    val myId = meshService?.getMyId()
                    Log.d(TAG, "getMyId() fallback result: $myId")
                    if (!myId.isNullOrEmpty()) {
                        // Check for node change even with fallback ID
                        checkForNodeChange(myId)
                        _localNodeIdOrNickname.value = myId
                        Log.d(TAG, "Using getMyId() as fallback: $myId")
                        // Set connection state to Connected since we have a valid node ID
                        _connectionState.value = MeshConnectionState.Connected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
                        Log.i(TAG, "Mesh device connection confirmed via fallback ID - connection state set to Connected")
                    } else {
                        Log.w(TAG, "getMyId() returned null or empty")
                        // No mesh device connection - set connection state to ServiceConnected
                        Log.i(TAG, "No mesh device connection detected - setting connection state to ServiceConnected")
                        _connectionState.value = MeshConnectionState.ServiceConnected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
                        return
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getMyId() fallback also failed: ${e.message}")
                    // No mesh device connection - set connection state to ServiceConnected
                    Log.i(TAG, "No mesh device connection detected - setting connection state to ServiceConnected")
                    _connectionState.value = MeshConnectionState.ServiceConnected(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
                    return
                }
            }
            
            // Step 2: Get all nodes (equivalent to NODE_INFO packets in Bluetooth)
            Log.d(TAG, "Step 2: Getting all nodes...")
            _configDownloadStep.value = ConfigDownloadStep.DownloadingNodeInfo
            updateConfigStepCounter(ConfigDownloadStep.DownloadingNodeInfo)
            
            val nodes = try {
                meshService?.getNodes() ?: emptyList()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException calling getNodes: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception calling getNodes: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                emptyList()
            }
            Log.d(TAG, "Received ${nodes.size} nodes from AIDL service")
            
            // Process each node using base class handler
            nodes.forEach { nodeInfo ->
                val meshProtosNodeInfo = nodeInfo.toMeshProtosNodeInfo()
                handleNodeInfo(meshProtosNodeInfo)
            }

            // Step 3: Get channel set (equivalent to CHANNEL packets in Bluetooth)
            Log.d(TAG, "Step 3: Getting channel set...")
            _configDownloadStep.value = ConfigDownloadStep.DownloadingChannel
            updateConfigStepCounter(ConfigDownloadStep.DownloadingChannel)
            
            val channelSetBytes = try {
                meshService?.getChannelSet()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException calling getChannelSet: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected exception calling getChannelSet: ${e.javaClass.simpleName}: ${e.message}")
                Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
                null
            }
            
            if (channelSetBytes != null) {
                try {
                    val channelSet = AppOnlyProtos.ChannelSet.parseFrom(channelSetBytes)
                    Log.d(TAG, "Received channel set with ${channelSet.settingsCount} channels")
                    
                    // Process each channel in the set (equivalent to individual CHANNEL packets in Bluetooth)
                    channelSet.settingsList.forEachIndexed { index, channelSettings ->
                        // Convert AppOnlyProtos.ChannelSettings to ChannelProtos.Channel
                        val channelBuilder = ChannelProtos.Channel.newBuilder()
                            .setIndex(index)
                            .setRole(if (index == 0) ChannelProtos.Channel.Role.PRIMARY else ChannelProtos.Channel.Role.SECONDARY)
                        
                        val settingsBuilder = ChannelProtos.ChannelSettings.newBuilder()
                            .setName(channelSettings.name)
                            .setPsk(ByteString.copyFrom(channelSettings.psk.toByteArray()))
                            .setUplinkEnabled(channelSettings.uplinkEnabled)
                            .setDownlinkEnabled(channelSettings.downlinkEnabled)
                        
                        // Add module settings if available
                        if (channelSettings.hasModuleSettings()) {
                            val moduleSettingsBuilder = ChannelProtos.ModuleSettings.newBuilder()
                                .setPositionPrecision(channelSettings.moduleSettings.positionPrecision)
                                .setIsClientMuted(channelSettings.moduleSettings.isClientMuted)
                            settingsBuilder.setModuleSettings(moduleSettingsBuilder.build())
                        }
                        
                        channelBuilder.setSettings(settingsBuilder.build())
                        val channel = channelBuilder.build()
                        
                        handleChannelUpdate(channel)
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to parse channel set", e)
                }
            } else {
                Log.w(TAG, "Channel set is null")
            }
            
            // Handshake complete
            handshakeComplete.set(true)
            _configDownloadStep.value = ConfigDownloadStep.Complete
            Log.i(TAG, "AIDL handshake complete!")
            
            // Restore selected channel from preferences after handshake is complete
            val prefs = context.getSharedPreferences("channel_prefs", Context.MODE_PRIVATE)
            val savedChannelId = prefs.getString("selected_channel_id", null)
            if (savedChannelId != null) {
                Log.d(TAG, "Restoring saved channel selection: $savedChannelId")
                selectChannel(savedChannelId)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "AIDL handshake failed", e)
            _configDownloadStep.value = ConfigDownloadStep.Error("Handshake failed: ${e.message}")
            _connectionState.value = MeshConnectionState.Error("AIDL handshake failed: ${e.message}")
        }
    }
    
    private fun updateConfigStepCounter(step: ConfigDownloadStep) {
        val currentCounters = _configStepCounters.value.toMutableMap()
        val currentCount = currentCounters[step] ?: 0
        currentCounters[step] = currentCount + 1
        _configStepCounters.value = currentCounters
    }
    
    // MeshProtocol interface implementation
    
    override fun scanForDevices(onResult: (com.tak.lite.di.DeviceInfo) -> Unit, onScanFinished: () -> Unit) {
        // For AIDL, we can return the Meshtastic app as a "device"
        val meshtasticInstalled = try {
            context.packageManager.getPackageInfo("com.geeksville.mesh", 0)
            true
        } catch (e: Exception) {
            false
        }
        
        if (meshtasticInstalled) {
            onResult(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App"))
        }
        onScanFinished()
    }
    
    override fun connectToDevice(deviceInfo: com.tak.lite.di.DeviceInfo, onConnected: (Boolean) -> Unit) {
        when (deviceInfo) {
            is com.tak.lite.di.DeviceInfo.AidlDevice -> {
                // Reset user-initiated disconnect flag when user wants to connect
                userInitiatedDisconnect = false
                
                if (isMeshServiceBound) {
                    onConnected(true)
                } else {
                    // Set connection state to Connecting before attempting to bind
                    _connectionState.value = MeshConnectionState.Connecting
                    Log.d(TAG, "Connection state set to Connecting")
                    
                    val success = bindMeshService()
                    onConnected(success)
                }
            }
            is com.tak.lite.di.DeviceInfo.BluetoothDevice -> {
                // AIDL protocol doesn't support Bluetooth devices
                onConnected(false)
            }
            is com.tak.lite.di.DeviceInfo.NetworkDevice -> {
                // AIDL protocol doesn't support network devices
                onConnected(false)
            }
        }
    }
    
    override fun disconnectFromDevice() {
        Log.i(TAG, "=== User-initiated disconnect requested ===")
        Log.d(TAG, "Instance ID: ${this.hashCode()}")
        userInitiatedDisconnect = true
        
        // Stop providing location if service is available
        if (meshService != null) {
            try {
                meshService?.stopProvideLocation()
                Log.d(TAG, "Called stopProvideLocation on mesh service")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to call stopProvideLocation: ${e.message}")
            }
        } else {
            Log.d(TAG, "Mesh service is null, skipping stopProvideLocation")
        }

        cleanup()
        cleanupState()
        _connectionState.value = MeshConnectionState.Disconnected
        Log.i(TAG, "Connection state set to Disconnected")
        Log.i(TAG, "=== User-initiated disconnect complete ===")
    }
    
    // Packets are queued on the abstract base protocol class by each specific send function
    // The queue is processed and calls sendPacket here if the AIDL protocol is selected
    // Handles the actual transmission through this protocols device interface (AIDL)
    override fun sendPacket(packet: MeshPacket): Boolean {
        if (meshService == null) {
            Log.w(TAG, "MeshService is not bound, cannot send packet")
            queueResponse.remove(packet.id)?.complete(false)
            return false
        }
        
        try {
            Log.d(TAG, "Converting MeshPacket to DataPacket for AIDL send")
            
            // Convert MeshPacket to DataPacket
            val dataPacket = packet.toDataPacket()
            
            Log.d(TAG, "Sending DataPacket via AIDL service: from=${dataPacket.from}, to=${dataPacket.to}, dataType=${dataPacket.dataType}")
            Log.d(TAG, "Local node ID: ${_localNodeIdOrNickname.value}")
            Log.d(TAG, "DataPacket: $dataPacket")
            
            // Send via AIDL service
            meshService?.send(dataPacket)
            
            Log.d(TAG, "Packet id=${packet.id} sent successfully via AIDL service")
            queueResponse.remove(packet.id)?.complete(true)
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send packet id=${packet.id} via AIDL service", e)
            queueResponse.remove(packet.id)?.complete(false)
            return false
        }
    }

    override fun getPeerPublicKey(peerId: String): ByteArray? {
        // Not supported in AIDL mode
        return null
    }

    override fun forceReset() {
        Log.i(TAG, "Force reset requested")
        userInitiatedDisconnect = false // Ensure auto-rebind logic will run
        // Avoid calling disconnectFromDevice() to prevent flipping userInitiatedDisconnect
        // Perform targeted cleanup and rebind
        cleanup()
        cleanupState()
        // Reconnect after a short delay
        CoroutineScope(coroutineContext).launch {
            kotlinx.coroutines.delay(1000)
            // Ensure receiver is registered before attempting to bind again
            if (!isReceiverRegistered) {
                registerMeshReceiver()
            }
            bindMeshService()
        }
    }
    
    override fun cleanupState() {
        Log.i(TAG, "Cleaning up AIDL protocol state")
        cleanup()
        super.cleanupState()
    }
    
    override fun isReadyForNewConnection(): Boolean {
        return !isMeshServiceBound && !userInitiatedDisconnect
    }

    override fun getDiagnosticInfo(): String {
        val meshtasticInstalled = try {
            context.packageManager.getPackageInfo("com.geeksville.mesh", 0)
            true
        } catch (e: Exception) {
            false
        }

        val meshtasticRunning = try {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val runningProcesses = activityManager.runningAppProcesses
            runningProcesses?.any { it.processName == "com.geeksville.mesh" } ?: false
        } catch (e: Exception) {
            false
        }

        val serviceResponsive = isServiceResponsive()
        val activityContextAvailable = activityContextProvider.getActivityContext() != null

        return "AIDL Protocol State: ${_connectionState.value}, " +
               "Service Bound: $isMeshServiceBound, " +
               "Service Responsive: $serviceResponsive, " +
               "User Initiated Disconnect: $userInitiatedDisconnect, " +
               "Handshake Complete: $handshakeComplete, " +
               "Config Step: ${_configDownloadStep.value}, " +
               "Meshtastic Installed: $meshtasticInstalled, " +
               "Meshtastic Running: $meshtasticRunning, " +
               "My ID: ${_localNodeIdOrNickname.value}, " +
               "Broadcast Receiver Registered: $isReceiverRegistered, " +
               "Activity Context Available: $activityContextAvailable, " +
               "Using Application Context: true"
    }
    
    fun cleanup() {
        Log.i(TAG, "=== Cleaning up AIDL protocol ===")
        Log.d(TAG, "Instance ID: ${this.hashCode()}")
        Log.d(TAG, "Broadcast receiver registered: $isReceiverRegistered")
        Log.d(TAG, "Using application context for all operations")
        
        unregisterMeshReceiver()
        unbindMeshService()
        
        // Ensure tracking state is reset
        isReceiverRegistered = false
        Log.d(TAG, "AIDL protocol cleanup complete - broadcast receiver tracking reset")
        Log.i(TAG, "=== AIDL protocol cleanup complete ===")
    }
    
    fun checkMeshtasticAppStatus(): String {
        val packageManager = context.packageManager
        
        // Check if package exists
        val packageExists = try {
            packageManager.getPackageInfo("com.geeksville.mesh", 0)
            true
        } catch (e: Exception) {
            false
        }
        
        if (!packageExists) {
            return "Meshtastic app not installed"
        }
        
        // Check if app is accessible
        val appAccessible = try {
            val appInfo = packageManager.getApplicationInfo("com.geeksville.mesh", 0)
            val appLabel = packageManager.getApplicationLabel(appInfo)
            "Meshtastic app accessible: $appLabel"
        } catch (e: Exception) {
            "Meshtastic app installed but not accessible: ${e.message}"
        }
        
        // Check if service is responsive
        val serviceResponsive = if (isMeshServiceBound && meshService != null) {
            try {
                val connectionState = meshService?.connectionState()
                "Service responsive: $connectionState"
            } catch (e: Exception) {
                "Service not responsive: ${e.message}"
            }
        } else {
            "Service not bound"
        }
        
        return "$appAccessible, $serviceResponsive"
    }

    override fun syncAmbientLedWithStatus(status: com.tak.lite.model.UserStatus) {
        Log.d(TAG, "Syncing ambient LED with status via AIDL: $status")
        
        if (meshService == null) {
            Log.w(TAG, "MeshService is not bound, cannot sync ambient LED")
            return
        }
        
        try {
            // Convert status to RGB values
            val (red, green, blue) = when (status) {
                com.tak.lite.model.UserStatus.RED -> Triple(255, 0, 0)
                com.tak.lite.model.UserStatus.YELLOW -> Triple(255, 235, 59)
                com.tak.lite.model.UserStatus.BLUE -> Triple(0, 0, 255)
                com.tak.lite.model.UserStatus.ORANGE -> Triple(255, 87, 34)
                com.tak.lite.model.UserStatus.VIOLET -> Triple(225, 190, 231)
                com.tak.lite.model.UserStatus.GREEN -> Triple(0, 255, 0)
            }
            
            // Create ambient lighting config
            val ambientLightingConfig = com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.AmbientLightingConfig.newBuilder()
                .setLedState(true)  // Turn LED on
                .setCurrent(10)     // Default current
                .setRed(red)
                .setGreen(green)
                .setBlue(blue)
                .build()
            
            // Create module config with ambient lighting
            val moduleConfig = com.geeksville.mesh.ModuleConfigProtos.ModuleConfig.newBuilder()
                .setAmbientLighting(ambientLightingConfig)
                .build()
            
            // Send module config via AIDL service
            val requestId = meshService?.getPacketId() ?: 0
            val destNum = 0xffffffffL.toInt() // Broadcast to all nodes
            
            Log.d(TAG, "Sending module config via AIDL: requestId=$requestId, destNum=$destNum")
            meshService?.setModuleConfig(requestId, destNum, moduleConfig.toByteArray())
            
            Log.d(TAG, "Module config sent successfully via AIDL for ambient LED sync")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sync ambient LED via AIDL", e)
        }
    }
    
    /**
     * Check if the AIDL service is still responsive by making a simple call
     * @return true if the service responds, false otherwise
     */
    fun isServiceResponsive(): Boolean {
        return if (isMeshServiceBound && meshService != null) {
            try {
                // Make a simple call to test service responsiveness
                meshService?.connectionState()
                true
            } catch (e: Exception) {
                Log.w(TAG, "Service not responsive: ${e.message}")
                false
            }
        } else {
            false
        }
    }

    /**
     * Get the local user's shortname and hwmodel for display purposes
     * @return Pair of shortname and hwmodel string, or null if not available
     */
    override fun getLocalUserInfo(): Pair<String, String>? {
        return super.getLocalUserInfoInternal()
    }

    // Data conversion utilities
    private fun DataPacket.toMeshPacket(): MeshPacket {
        try {
        val builder = MeshPacket.newBuilder()
            .setId(this.id)
            .setChannel(this.channel)
            .setWantAck(this.wantAck)
            .setHopLimit(this.hopLimit)
        
        // Convert from/to node IDs
        this.from?.let { fromId ->
            if (fromId != DataPacket.ID_LOCAL) {
                val nodeNum = DataPacket.idToDefaultNodeNum(fromId)
                if (nodeNum != null) {
                    builder.setFrom(nodeNum.toInt())
                } else {
                    Log.w(TAG, "Could not convert from ID '$fromId' to node number")
                }
            }
        }
        
        this.to?.let { toId ->
            if (toId != DataPacket.ID_BROADCAST) {
                val nodeNum = DataPacket.idToDefaultNodeNum(toId)
                if (nodeNum != null) {
                    builder.setTo(nodeNum.toInt())
                } else {
                    Log.w(TAG, "Could not convert to ID '$toId' to node number")
                }
            } else {
                builder.setTo(0xffffffffL.toInt()) // Broadcast address
            }
        }
        
        // Create decoded data
        val dataBuilder = MeshProtos.Data.newBuilder()
            .setPortnum(Portnums.PortNum.forNumber(this.dataType) ?: Portnums.PortNum.UNKNOWN_APP)
            .setPayload(ByteString.copyFrom(this.bytes ?: ByteArray(0)))
            .setRequestId(this.id)
        
        builder.setDecoded(dataBuilder.build())
        return builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting DataPacket to MeshPacket: ${e.message}", e)
            throw e
        }
    }
    
    private fun MeshPacket.toDataPacket(): DataPacket {
        try {
            // Extract decoded data
            val decodedData = this.decoded
            if (decodedData == null) {
                Log.w(TAG, "MeshPacket has no decoded data")
                throw IllegalArgumentException("MeshPacket has no decoded data")
            }
            
            // Convert node numbers to IDs
            Log.d(TAG, "MeshPacket from: ${this.from}")
            var fromId = DataPacket.ID_LOCAL
            
            // If fromId is set, use it
            if (this.from != 0) {
                Log.w(TAG, "ID set on packet, using it")
                fromId = DataPacket.nodeNumToDefaultId(this.from)
            }
            
            val toId = if (this.to == 0xffffffffL.toInt()) {
                DataPacket.ID_BROADCAST
            } else {
                DataPacket.nodeNumToDefaultId(this.to)
            }
            
            // Extract payload bytes
            val payloadBytes = decodedData.payload.toByteArray()
            
            // Get port number
            val dataType = decodedData.portnum.number
            
            // Create DataPacket
            val dataPacket = DataPacket(
                to = toId,
                bytes = payloadBytes,
                dataType = dataType,
                from = fromId,
                time = System.currentTimeMillis(),
                id = this.id,
                status = MessageStatus.UNKNOWN, // Default status for outgoing packets
                hopLimit = this.hopLimit,
                channel = this.channel,
                wantAck = this.wantAck
            )

            Log.d(TAG, "DataPacket: $dataPacket")
            return dataPacket
            
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MeshPacket to DataPacket: ${e.message}", e)
            throw e
        }
    }
    
    private fun NodeInfo.toMeshProtosNodeInfo(): MeshProtos.NodeInfo {
        try {
        val builder = MeshProtos.NodeInfo.newBuilder()
            .setNum(this.num)
            .setLastHeard(this.lastHeard)
            .setSnr(this.snr)
            .setChannel(this.channel)
            .setHopsAway(this.hopsAway)
        
        // Convert user info if available
        this.user?.let { user ->
            val userBuilder = MeshProtos.User.newBuilder()
                .setShortName(user.shortName)
                .setLongName(user.longName)
                .setId(user.id)
                .setHwModel(user.hwModel)
                .setIsLicensed(user.isLicensed)
                .setRoleValue(user.role)
            
            // This is a hack because AIDL interface doesn't provide PK to us,
            // but the code expects it to do things like send over the magic PKC channel
            // Generate a deterministic spoofed public key based on the user ID
            // This ensures the same user always gets the same public key
            val spoofedPublicKey = generateSpoofedPublicKey(user.id)
            userBuilder.setPublicKey(ByteString.copyFrom(spoofedPublicKey))
            
            builder.setUser(userBuilder.build())
        }
        
        // Convert position if available
        this.position?.let { position ->
            val positionBuilder = MeshProtos.Position.newBuilder()
                .setLatitudeI((position.latitude * 1e7).toInt())
                .setLongitudeI((position.longitude * 1e7).toInt())
                .setAltitude(position.altitude)
                .setTime(position.time)
                .setSatsInView(position.satellitesInView)
                .setGroundSpeed(position.groundSpeed)
                .setGroundTrack(position.groundTrack)
                .setPrecisionBits(position.precisionBits)
            builder.setPosition(positionBuilder.build())
        }
        
        return builder.build()
        } catch (e: Exception) {
            Log.e(TAG, "Error converting NodeInfo to MeshProtos.NodeInfo: ${e.message}", e)
            throw e
        }
    }
    
    /**
     * Generates a deterministic spoofed public key based on the user ID.
     * This ensures that the same user always gets the same public key,
     * which is necessary for private messaging to work consistently.
     */
    private fun generateSpoofedPublicKey(userId: String): ByteArray {
        try {
            // Use a simple hash-based approach to generate a deterministic "public key"
            // This creates a 32-byte array that looks like a public key
            val hash = userId.hashCode()
            val keyBytes = ByteArray(32)
            
            // Fill the key with deterministic data based on the user ID
            for (i in keyBytes.indices) {
                keyBytes[i] = ((hash + i * 7) % 256).toByte()
            }
            
            // Set the first byte to indicate this is a "spoofed" key (for debugging)
            keyBytes[0] = 0xAA.toByte()
            
            Log.d(TAG, "Generated spoofed public key for user $userId: ${keyBytes.take(8).joinToString(", ") { "0x%02X".format(it) }}...")
            
            return keyBytes
        } catch (e: Exception) {
            Log.e(TAG, "Error generating spoofed public key for user $userId", e)
            // Return a fallback key if generation fails
            return ByteArray(32) { 0x00 }
        }
    }
    
    private fun MyNodeInfo.toMeshProtosMyNodeInfo(): MeshProtos.MyNodeInfo {
        try {
            Log.d(TAG, "=== MyNodeInfo.toMeshProtosMyNodeInfo() Conversion Debug ===")
            Log.d(TAG, "Input MyNodeInfo details:")
            Log.d(TAG, "  - myNodeNum (signed): ${this.myNodeNum}")
            Log.d(TAG, "  - myNodeNum (hex): 0x${this.myNodeNum.toString(16)}")
            Log.d(TAG, "  - myNodeNum (unsigned): ${(this.myNodeNum.toLong() and 0xFFFFFFFFL)}")
            Log.d(TAG, "  - minAppVersion: ${this.minAppVersion}")
            Log.d(TAG, "  - hasGPS: ${this.hasGPS}")
            Log.d(TAG, "  - model: ${this.model}")
            Log.d(TAG, "  - firmwareVersion: ${this.firmwareVersion}")
            Log.d(TAG, "  - deviceId: ${this.deviceId}")
            
            val builder = MeshProtos.MyNodeInfo.newBuilder()
            
            // Set myNodeNum (always available in AIDL MyNodeInfo)
            // Pass the raw signed value - the base protocol will handle the unsigned conversion
            Log.d(TAG, "Setting myNodeNum: ${this.myNodeNum} (raw signed value)")
            builder.setMyNodeNum(this.myNodeNum)
            
            // Set minAppVersion (always available in AIDL MyNodeInfo)
            Log.d(TAG, "Setting minAppVersion: ${this.minAppVersion}")
            builder.setMinAppVersion(this.minAppVersion)
            
            val result = builder.build()
            Log.d(TAG, "Conversion result:")
            Log.d(TAG, "  - myNodeNum: ${result.myNodeNum}")
            Log.d(TAG, "  - minAppVersion: ${result.minAppVersion}")
            Log.d(TAG, "  - Expected unsigned value: ${(this.myNodeNum.toLong() and 0xFFFFFFFFL)}")
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Error converting MyNodeInfo to MeshProtos.MyNodeInfo: ${e.message}", e)
            Log.e(TAG, "Stack trace: ${e.stackTraceToString()}")
            throw e
        }
    }
    
    private fun MessageStatus.toBaseMessageStatus(): BaseMessageStatus {
        return when (this) {
            MessageStatus.UNKNOWN -> BaseMessageStatus.FAILED
            MessageStatus.RECEIVED -> BaseMessageStatus.RECEIVED
            MessageStatus.QUEUED -> BaseMessageStatus.SENDING
            MessageStatus.ENROUTE -> BaseMessageStatus.SENT
            MessageStatus.DELIVERED -> BaseMessageStatus.DELIVERED
            MessageStatus.ERROR -> BaseMessageStatus.ERROR
        }
    }

    // Missing handler functions
    private fun handleNodeChange(nodeInfo: NodeInfo?) {
        if (nodeInfo == null) {
            Log.w(TAG, "Received null NodeInfo in handleNodeChange")
            return
        }
        
        Log.d(TAG, "Handling node change for node ${nodeInfo.num}")

        // Convert to protobuf format and use base class handler
        val meshProtosNodeInfo = nodeInfo.toMeshProtosNodeInfo()
        handleNodeInfo(meshProtosNodeInfo)
    }
    
    private fun handleTextMessage(dataPacket: DataPacket?) {
        if (dataPacket == null) {
            Log.w(TAG, "Received null DataPacket in handleTextMessage")
            return
        }
        
        Log.d(TAG, "Handling text message from ${dataPacket.from} to ${dataPacket.to}")
        
        // Convert to protobuf format and use base class handler
        val meshPacket = dataPacket.toMeshPacket()
        handlePacket(meshPacket, MeshProtos.FromRadio.PayloadVariantCase.PACKET)
    }
    
    private fun handleAnnotation(dataPacket: DataPacket?) {
        if (dataPacket == null) {
            Log.w(TAG, "Received null DataPacket in handleAnnotation")
            return
        }
        
        Log.d(TAG, "Handling annotation from ${dataPacket.from}")
        
        // Convert to protobuf format and use base class handler
        val meshPacket = dataPacket.toMeshPacket()
        handlePacket(meshPacket, MeshProtos.FromRadio.PayloadVariantCase.PACKET)
    }
    
    private fun handleRoutingApp(dataPacket: DataPacket?) {
        if (dataPacket == null) {
            Log.w(TAG, "Received null DataPacket in handleRoutingApp")
            return
        }
        
        Log.d(TAG, "Handling routing packet from ${dataPacket.from}")
        
        // Convert to protobuf format and use base class handler
        val meshPacket = dataPacket.toMeshPacket()
        handlePacket(meshPacket, MeshProtos.FromRadio.PayloadVariantCase.PACKET)
    }
    
    private fun handleMessageStatus(packetId: Int, status: MessageStatus?) {
        if (status == null) {
            Log.w(TAG, "Received null MessageStatus for packet $packetId")
            return
        }
        
        Log.d(TAG, "Handling message status for packet $packetId: $status")
        
        // Convert to base message status
        val baseStatus = status.toBaseMessageStatus()
        Log.d(TAG, "Converted status $status to base status $baseStatus")
        
        // Find the packet in flight messages and update its status
        val packet = inFlightMessages[packetId]
        if (packet != null) {
            Log.d(TAG, "Found packet $packetId in inFlightMessages, current count: ${inFlightMessages.size}")
            updateMessageStatusForPacket(packet, baseStatus)
            
            // Only remove packets from inFlightMessages for successful final statuses
            // This allows the timeout system to retry failed messages
            when (baseStatus) {
                BaseMessageStatus.DELIVERED, BaseMessageStatus.RECEIVED -> {

                    Log.d(TAG, "Message $packetId reached successful final status $baseStatus, removing from inFlightMessages")
                    inFlightMessages.remove(packetId)
                    messageRetryCount.remove(packetId)
                    timeoutJobManager.cancelTimeout(packetId)
                    Log.d(TAG, "Removed packet $packetId from tracking, remaining inFlightMessages: ${inFlightMessages.size}")
                }
                BaseMessageStatus.FAILED, BaseMessageStatus.ERROR -> {
                    // For failed statuses, don't remove from inFlightMessages yet
                    // Let the timeout system handle retries
                    Log.d(TAG, "Message $packetId reached failed status $baseStatus, keeping in inFlightMessages for potential retry")
                    // Don't remove from inFlightMessages - let timeout system handle retries
                }
                BaseMessageStatus.SENT -> {
                    // For SENT status, we don't remove from inFlightMessages yet (waiting for delivery confirmation)
                    Log.d(TAG, "Message $packetId reached SENT status, waiting for delivery confirmation")
                    // Don't cancel timeout yet - let it wait for delivery confirmation
                }
                BaseMessageStatus.SENDING -> {
                    Log.d(TAG, "Message $packetId reached SENDING status")
                }
            }
        } else {
            Log.w(TAG, "Could not find packet $packetId in flight messages")
        }
    }
    
    private fun handleNodeInfoPacket(dataPacket: DataPacket?) {
        if (dataPacket == null) {
            Log.w(TAG, "Received null DataPacket in handleNodeInfoPacket")
            return
        }
        
        Log.d(TAG, "Handling node info packet from ${dataPacket.from}")
        
        // Convert to protobuf format and use base class handler
        val meshPacket = dataPacket.toMeshPacket()
        handlePacket(meshPacket, MeshProtos.FromRadio.PayloadVariantCase.PACKET)
    }
    
    private fun handlePositionPacket(dataPacket: DataPacket?) {
        if (dataPacket == null) {
            Log.w(TAG, "Received null DataPacket in handlePositionPacket")
            return
        }
        
        Log.d(TAG, "Handling position packet from ${dataPacket.from}")
        
        // Convert to protobuf format and use base class handler
        val meshPacket = dataPacket.toMeshPacket()
        handlePacket(meshPacket, MeshProtos.FromRadio.PayloadVariantCase.PACKET)
    }
} 