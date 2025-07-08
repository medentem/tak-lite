package com.tak.lite

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tak.lite.di.ConfigDownloadStep
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.di.MeshProtocol
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.map.MapController
import com.tak.lite.ui.settings.PredictionAdvancedSettingsDialog
import com.tak.lite.util.BillingManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : BaseActivity() {
    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider
    @Inject lateinit var billingManager: BillingManager
    private lateinit var mapModeSpinner: AutoCompleteTextView
    private lateinit var endBeepSwitch: SwitchMaterial
    private lateinit var minLineSegmentDistEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var bluetoothConnectButton: Button
    private lateinit var bluetoothStatusText: TextView
    private lateinit var aidlConnectButton: com.google.android.material.button.MaterialButton
    private lateinit var aidlStatusText: TextView
    private lateinit var darkModeSpinner: AutoCompleteTextView
    private lateinit var keepScreenAwakeSwitch: SwitchMaterial
    private lateinit var simulatePeersSwitch: SwitchMaterial
    private lateinit var simulatedPeersCountEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var simulatedPeersCountLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var meshNetworkTypeLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var unlockAppButton: com.google.android.material.button.MaterialButton
    private var connectedDevice: BluetoothDevice? = null
    
    private lateinit var currentProtocol: com.tak.lite.di.MeshProtocol
    
    private val mapModeOptions = listOf("Last Used", "Street", "Satellite", "Hybrid")
    private val mapModeEnumValues = listOf(
        MapController.MapType.LAST_USED,
        MapController.MapType.STREETS,
        MapController.MapType.SATELLITE,
        MapController.MapType.HYBRID
    )
    private val darkModeOptions = listOf("Use Phone Setting", "Always Dark", "Always Light")
    private val darkModeValues = listOf("system", "dark", "light")
    private val BLUETOOTH_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }
    private val REQUEST_CODE_BLUETOOTH_PERMISSIONS = 1002
    private lateinit var configProgressBar: android.widget.ProgressBar
    private lateinit var configProgressText: TextView
    private lateinit var backgroundProcessingSwitch: SwitchMaterial
    private lateinit var compassStatusText: TextView
    private lateinit var compassQualityText: TextView
    private lateinit var compassCalibrateButton: com.google.android.material.button.MaterialButton
    private lateinit var showPredictionOverlaySwitch: SwitchMaterial
    private lateinit var predictionAdvancedLink: TextView
    private val REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE = 2003
    private val REQUEST_CODE_NOTIFICATION_PERMISSION = 3001
    private val REQUEST_CODE_ALL_PERMISSIONS = 4001
    private val REQUEST_CODE_COMPASS_CALIBRATION = 5001
    
    // Protocol observation jobs
    private var protocolJob: kotlinx.coroutines.Job? = null
    private var connectionStateJob: kotlinx.coroutines.Job? = null
    private var configStepJob: kotlinx.coroutines.Job? = null
    private var configCounterJob: kotlinx.coroutines.Job? = null
    
    // Compass sensor monitoring
    private lateinit var sensorManager: android.hardware.SensorManager
    private var currentSensorAccuracy = android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE
    private val sensorListener = object : android.hardware.SensorEventListener {
        override fun onSensorChanged(event: android.hardware.SensorEvent?) {
            // We only care about accuracy changes for compass status
        }
        
        override fun onAccuracyChanged(sensor: android.hardware.Sensor?, accuracy: Int) {
            if (sensor?.type == android.hardware.Sensor.TYPE_ROTATION_VECTOR || 
                sensor?.type == android.hardware.Sensor.TYPE_MAGNETIC_FIELD) {
                currentSensorAccuracy = accuracy
                updateCompassStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        title = "Settings"

        mapModeSpinner = findViewById(R.id.mapModeSpinner)
        endBeepSwitch = findViewById(R.id.endBeepSwitch)
        minLineSegmentDistEditText = findViewById(R.id.minLineSegmentDistEditText)
        bluetoothConnectButton = findViewById(R.id.bluetoothConnectButton)
        bluetoothStatusText = findViewById(R.id.bluetoothStatusText)
        aidlConnectButton = findViewById(R.id.aidlConnectButton)
        aidlStatusText = findViewById(R.id.aidlStatusText)
        darkModeSpinner = findViewById(R.id.darkModeSpinner)
        keepScreenAwakeSwitch = findViewById(R.id.keepScreenAwakeSwitch)
        simulatePeersSwitch = findViewById(R.id.simulatePeersSwitch)
        simulatedPeersCountEditText = findViewById(R.id.simulatedPeersCountEditText)
        simulatedPeersCountLayout = findViewById(R.id.simulatedPeersCountLayout)
        meshNetworkTypeLayout = findViewById(R.id.meshNetworkTypeLayout)
        unlockAppButton = findViewById(R.id.unlockAppButton)
        configProgressBar = findViewById(R.id.configProgressBar)
        configProgressText = findViewById(R.id.configProgressText)
        backgroundProcessingSwitch = findViewById(R.id.backgroundProcessingSwitch)
        compassStatusText = findViewById(R.id.compassStatusText)
        compassQualityText = findViewById(R.id.compassQualityText)
        compassCalibrateButton = findViewById(R.id.compassCalibrateButton)
        showPredictionOverlaySwitch = findViewById(R.id.showPredictionOverlaySwitch)
        predictionAdvancedLink = findViewById(R.id.predictionAdvancedLink)

        // Check premium status and update UI accordingly
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                billingManager.isPremium.collectLatest { isPremium ->
                    val inTrial = billingManager.isInTrialPeriod()
                    updateMeshSettingsVisibility(isPremium || inTrial)
                }
            }
        }

        // Setup unlock button
        unlockAppButton.setOnClickListener {
            showPurchaseDialog()
        }

        // Setup map mode spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mapModeOptions)
        mapModeSpinner.setAdapter(adapter)

        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val savedMapMode = prefs.getString("startup_map_mode", "LAST_USED")
        val selectedIndex = mapModeEnumValues.indexOfFirst { it.name == savedMapMode } .takeIf { it >= 0 } ?: 0
        mapModeSpinner.setText(mapModeOptions[selectedIndex], false)

        mapModeSpinner.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString("startup_map_mode", mapModeEnumValues[position].name).apply()
        }

        // Setup end of transmission beep switch
        val beepEnabled = prefs.getBoolean("end_of_transmission_beep", true)
        endBeepSwitch.isChecked = beepEnabled
        endBeepSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("end_of_transmission_beep", isChecked).apply()
        }

        // Load and set minimum line segment distance
        val minDist = prefs.getFloat("min_line_segment_dist_miles", 1.0f)
        minLineSegmentDistEditText.setText(minDist.toString())
        minLineSegmentDistEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = minLineSegmentDistEditText.text.toString().toFloatOrNull()
                if (value != null) {
                    prefs.edit().putFloat("min_line_segment_dist_miles", value).apply()
                }
            }
        }

        val savedDeviceName = prefs.getString("meshtastic_bt_device_name", null)
        val savedDeviceAddr = prefs.getString("meshtastic_bt_device_addr", null)
        if (savedDeviceName != null && savedDeviceAddr != null) {
            bluetoothStatusText.text = "Last connected: $savedDeviceName ($savedDeviceAddr)"
        }

        // Setup AIDL connect button
        aidlConnectButton.setOnClickListener {
            // Prevent rapid clicking by disabling the button temporarily
            aidlConnectButton.isEnabled = false
            
            // Use current protocol reference
            val protocol = currentProtocol
            val connectionState = protocol.connectionState.value

            Log.d("SettingsActivity", "AIDL button clicked - protocol: ${protocol.javaClass.simpleName}, connectionState: $connectionState")
            Log.d("SettingsActivity", "AIDL button clicked - currentProtocol reference: ${currentProtocol.javaClass.simpleName}")

            // Verify we're using the correct protocol type
            if (protocol !is com.tak.lite.network.MeshtasticAidlProtocol) {
                Log.w("SettingsActivity", "AIDL button clicked but protocol is not MeshtasticAidlProtocol: ${protocol.javaClass.simpleName}")
                aidlConnectButton.isEnabled = true
                return@setOnClickListener
            }

            if (connectionState is MeshConnectionState.Connected) {
                Log.d("SettingsActivity", "AIDL disconnect button clicked - disconnecting...")
                protocol.disconnectFromDevice()
                // Re-enable button after a short delay
                aidlConnectButton.postDelayed({
                    aidlConnectButton.isEnabled = true
                }, 1000) // 1 second delay
            } else {
                val status = protocol.checkMeshtasticAppStatus()
                if (status.contains("not installed")) {
                    checkMeshtasticAppInstallation()
                    aidlConnectButton.isEnabled = true
                    return@setOnClickListener
                }
                if (status.contains("service may not be running")) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Meshtastic App Not Running")
                        .setMessage("Please open the Meshtastic app and keep it running in the background.")
                        .setPositiveButton("OK", null)
                        .show()
                    aidlConnectButton.isEnabled = true
                    return@setOnClickListener
                }
                Log.d("SettingsActivity", "AIDL connect button clicked - connecting...")
                protocol.connectToDevice(com.tak.lite.di.DeviceInfo.AidlDevice("Meshtastic App")) { _ ->
                    // UI will update via state observer
                    aidlConnectButton.isEnabled = true
                }
            }
        }

        bluetoothConnectButton.setOnClickListener {
            // Prevent rapid clicking by disabling the button temporarily
            bluetoothConnectButton.isEnabled = false

            // Use current protocol reference
            val protocol = currentProtocol
            val connectionState = protocol.connectionState.value

            Log.d("SettingsActivity", "Bluetooth button clicked - protocol: ${protocol.javaClass.simpleName}, connectionState: $connectionState")
            Log.d("SettingsActivity", "Bluetooth button clicked - currentProtocol reference: ${currentProtocol.javaClass.simpleName}")

            // Verify we're using the correct protocol type
            if (protocol !is com.tak.lite.network.MeshtasticBluetoothProtocol) {
                Log.w("SettingsActivity", "Bluetooth button clicked but protocol is not MeshtasticBluetoothProtocol: ${protocol.javaClass.simpleName}")
                bluetoothConnectButton.isEnabled = true
                return@setOnClickListener
            }

            if (connectionState is MeshConnectionState.Connected) {
                Log.d("SettingsActivity", "Bluetooth disconnect button clicked - disconnecting...")
                protocol.disconnectFromDevice()
                // Re-enable button after a short delay
                bluetoothConnectButton.postDelayed({
                    bluetoothConnectButton.isEnabled = true
                }, 1000) // 1 second delay
            } else {
                when {
                    !isBluetoothEnabled() -> {
                        promptEnableBluetooth()
                        bluetoothConnectButton.isEnabled = true
                    }
                    !isLocationEnabled() -> {
                        promptEnableLocation()
                        bluetoothConnectButton.isEnabled = true
                    }
                    !hasBluetoothPermissions() -> {
                        requestBluetoothPermissions()
                        bluetoothConnectButton.isEnabled = true
                    }
                    else -> {
                        Log.d("SettingsActivity", "Bluetooth connect button clicked - scanning...")
                        showDeviceScanDialog(currentProtocol)
                        // Button will be re-enabled when scan dialog closes or connection completes
                        // This is handled by the connection state observer
                    }
                }
            }
        }

        // Setup mesh network adapter spinner
        val meshNetworkTypeSpinner = findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.meshNetworkTypeSpinner)
        val meshNetworkOptions = listOf("Layer 2", "Meshtastic (Bluetooth)", "Meshtastic (App)")
        val meshAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, meshNetworkOptions)
        meshNetworkTypeSpinner.setAdapter(meshAdapter)

        // Get saved mesh type, defaulting to "Meshtastic"
        var savedMeshType = prefs.getString("mesh_network_type", null)
        if (savedMeshType == null) {
            // First time setup - save "Meshtastic" as default
            savedMeshType = "Meshtastic"
            prefs.edit().putString("mesh_network_type", savedMeshType).apply()
        }

        // Map display names to internal values
        val displayToInternal = mapOf(
            "Layer 2" to "Layer2",
            "Meshtastic (Bluetooth)" to "Meshtastic",
            "Meshtastic (App)" to "MeshtasticAidl"
        )
        val internalToDisplay = displayToInternal.entries.associate { it.value to it.key }

        // Convert internal value to display value
        val displayValue = internalToDisplay[savedMeshType] ?: "Meshtastic (Bluetooth)"
        meshNetworkTypeSpinner.setText(displayValue, false)

        // Show/hide the connect buttons and status texts based on initial value
        updateConnectionUIForMeshType(savedMeshType)

        meshNetworkTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            val displayType = meshNetworkOptions[position]
            val internalType = displayToInternal[displayType] ?: "Meshtastic"

            Log.d("SettingsActivity", "Protocol switching - from: ${currentProtocol.javaClass.simpleName} to: $internalType")
            Log.d("SettingsActivity", "About to save mesh_network_type preference to: $internalType")

            // Disconnect from current connection before switching protocols
            if (currentProtocol.connectionState.value is MeshConnectionState.Connected) {
                Log.d("SettingsActivity", "Disconnecting from current protocol before switching to $internalType")
                currentProtocol.disconnectFromDevice()
            }

            prefs.edit().putString("mesh_network_type", internalType).apply()
            meshProtocolProvider.updateProtocolType(internalType)
            Log.d("SettingsActivity", "Saved mesh_network_type preference. Current value: ${prefs.getString("mesh_network_type", "NOT_FOUND")}")

            // Force a small delay to see if the SharedPreferences listener triggers
            Log.d("SettingsActivity", "Waiting to see if SharedPreferences listener triggers...")

            // SharedPreferences listener should handle the protocol change
            Log.d("SettingsActivity", "SharedPreferences listener should handle protocol change to: $internalType")

            // Update UI for the selected mesh type
            updateConnectionUIForMeshType(internalType)

            // Check if Meshtastic app is installed when AIDL is selected
            if (internalType == "MeshtasticAidl") {
                checkMeshtasticAppInstallation()
            }
        }

        currentProtocol = meshProtocolProvider.protocol.value
        startProtocolObservers(currentProtocol)

        // Observe config download progress and connection state with dynamic protocol updates
        protocolJob = lifecycleScope.launch {
            Log.d("SettingsActivity", "Starting to observe meshProtocolProvider.protocol")
            Log.d("SettingsActivity", "Current protocol in provider: ${meshProtocolProvider.protocol.value.javaClass.simpleName}")
            Log.d("SettingsActivity", "Initial currentProtocol: ${currentProtocol.javaClass.simpleName}")
            meshProtocolProvider.protocol.collect { newProtocol ->
                Log.d("SettingsActivity", "Protocol observer triggered - new protocol: ${newProtocol.javaClass.simpleName}")
                Log.d("SettingsActivity", "Protocol reference comparison: currentProtocol !== newProtocol = ${currentProtocol !== newProtocol}")

                // Cancel existing protocol-specific jobs
                connectionStateJob?.cancel()
                configStepJob?.cancel()
                configCounterJob?.cancel()

                // Update local protocol reference
                if (currentProtocol !== newProtocol) {
                    Log.d("SettingsActivity", "Protocol reference changed from ${currentProtocol.javaClass.simpleName} to ${newProtocol.javaClass.simpleName}")
                    currentProtocol = newProtocol
                    Log.d("SettingsActivity", "Updated currentProtocol reference to: ${currentProtocol.javaClass.simpleName}")
                } else {
                    Log.d("SettingsActivity", "Protocol reference unchanged: ${currentProtocol.javaClass.simpleName}")
                }

                // Always start new protocol-specific observers (even if protocol didn't change)
                // This ensures observers are properly set up
                startProtocolObservers(newProtocol)
            }
        }

        // Setup background processing switch
        val backgroundEnabled = prefs.getBoolean("background_processing_enabled", false)
        backgroundProcessingSwitch.isChecked = backgroundEnabled
        backgroundProcessingSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("background_processing_enabled", isChecked).apply()
            if (isChecked) {
                val neededPermissions = mutableListOf<String>()
                if (!hasBluetoothPermissions()) {
                    neededPermissions.addAll(BLUETOOTH_PERMISSIONS)
                }
                if (Build.VERSION.SDK_INT >= 34 &&
                    ContextCompat.checkSelfPermission(this, "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE") != PackageManager.PERMISSION_GRANTED) {
                    neededPermissions.add("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    neededPermissions.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (neededPermissions.isNotEmpty()) {
                    ActivityCompat.requestPermissions(
                        this,
                        neededPermissions.toTypedArray(),
                        REQUEST_CODE_ALL_PERMISSIONS
                    )
                    return@setOnCheckedChangeListener
                }
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                stopService(Intent(this, MeshForegroundService::class.java))
            }
        }

        val showPacketSummarySwitch = findViewById<SwitchMaterial>(R.id.showPacketSummarySwitch)
        val showPacketSummaryEnabled = prefs.getBoolean("show_packet_summary", false)
        showPacketSummarySwitch.isChecked = showPacketSummaryEnabled
        showPacketSummarySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_packet_summary", isChecked).apply()
        }

        // Setup prediction overlay switch
        val showPredictionOverlayEnabled = prefs.getBoolean("show_prediction_overlay", true)
        showPredictionOverlaySwitch.isChecked = showPredictionOverlayEnabled
        showPredictionOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_prediction_overlay", isChecked).apply()
        }
        
        // Setup prediction advanced settings link
        predictionAdvancedLink.setOnClickListener {
            showPredictionAdvancedSettings()
        }

        // Setup compass calibration
        setupCompassCalibration()

        // Setup map dark mode spinner
        val darkModeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, darkModeOptions)
        darkModeSpinner.setAdapter(darkModeAdapter)
        val savedDarkMode = prefs.getString("dark_mode", "system")
        val darkModeIndex = darkModeValues.indexOf(savedDarkMode).takeIf { it >= 0 } ?: 0
        darkModeSpinner.setText(darkModeOptions[darkModeIndex], false)
        darkModeSpinner.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString("dark_mode", darkModeValues[position]).apply()
            applyDarkMode(darkModeValues[position])
        }

        // Setup keep screen awake switch
        val keepAwakeEnabled = prefs.getBoolean("keep_screen_awake", false)
        keepScreenAwakeSwitch.isChecked = keepAwakeEnabled
        setKeepScreenAwake(keepAwakeEnabled)
        keepScreenAwakeSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("keep_screen_awake", isChecked).apply()
            setKeepScreenAwake(isChecked)
        }

        // Load and set Simulate Peers settings
        val simulatePeersEnabled = prefs.getBoolean("simulate_peers_enabled", false)
        val simulatedPeersCount = prefs.getInt("simulated_peers_count", 3)
        simulatePeersSwitch.isChecked = simulatePeersEnabled
        simulatedPeersCountEditText.setText(simulatedPeersCount.toString())
        simulatedPeersCountLayout.isEnabled = simulatePeersEnabled
        simulatedPeersCountEditText.isEnabled = simulatePeersEnabled

        simulatePeersSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("simulate_peers_enabled", isChecked).apply()
            simulatedPeersCountLayout.isEnabled = isChecked
            simulatedPeersCountEditText.isEnabled = isChecked
        }
        simulatedPeersCountEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = simulatedPeersCountEditText.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 1
                simulatedPeersCountEditText.setText(value.toString())
                prefs.edit().putInt("simulated_peers_count", value).apply()
            }
        }

        currentProtocol = meshProtocolProvider.protocol.value
        
        // Initialize protocol observers for the initial protocol
        startProtocolObservers(currentProtocol)
        
        // Initialize button states after currentProtocol is set
        updateBluetoothButtonState()
    }

    private fun setupCompassCalibration() {
        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        
        // Register sensor listener for accuracy monitoring
        val rotationSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
        
        rotationSensor?.let { 
            sensorManager.registerListener(sensorListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
        magnetometer?.let { 
            sensorManager.registerListener(sensorListener, it, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
        }
        
        // Initialize compass status
        updateCompassStatus()
        
        // Setup calibration button
        compassCalibrateButton.setOnClickListener {
            startCompassCalibration()
        }
        
        // Monitor compass status changes
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Update status every 5 seconds while activity is active
                while (true) {
                    updateCompassStatus()
                    kotlinx.coroutines.delay(5000)
                }
            }
        }
    }

    private fun updateCompassStatus() {
        val sensorManager = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val rotationSensor = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_ROTATION_VECTOR)
        val magnetometer = sensorManager.getDefaultSensor(android.hardware.Sensor.TYPE_MAGNETIC_FIELD)
        
        if (rotationSensor == null && magnetometer == null) {
            compassStatusText.text = getString(R.string.compass_calibration_status, getString(R.string.compass_calibration_no_sensors))
            compassQualityText.text = getString(R.string.compass_calibration_quality, getString(R.string.compass_calibration_not_supported))
            compassCalibrateButton.isEnabled = false
            return
        }
        
        // Get current sensor accuracy from the sensor listener
        val currentAccuracy = getCurrentSensorAccuracy()
        val calibrationStatus = getCalibrationStatus(currentAccuracy)
        val compassQuality = getCompassQuality(currentAccuracy)
        
        // Get stored calibration data
        val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
        val lastCalibrationTime = prefs.getLong("calibration_timestamp", 0L)
        
        // Update status text
        val statusText = when (calibrationStatus) {
            com.tak.lite.ui.location.CalibrationStatus.EXCELLENT -> getString(R.string.compass_calibration_excellent)
            com.tak.lite.ui.location.CalibrationStatus.GOOD -> getString(R.string.compass_calibration_good)
            com.tak.lite.ui.location.CalibrationStatus.POOR -> getString(R.string.compass_calibration_poor)
            com.tak.lite.ui.location.CalibrationStatus.UNKNOWN -> getString(R.string.compass_calibration_needs_calibration)
        }
        compassStatusText.text = getString(R.string.compass_calibration_status, statusText)
        
        // Update quality text with additional info
        val qualityText = buildString {
            append(getString(R.string.compass_calibration_quality, compassQuality.name))
            if (lastCalibrationTime > 0) {
                val age = System.currentTimeMillis() - lastCalibrationTime
                val hours = age / (1000 * 60 * 60)
                if (hours < 1) append(" (calibrated <1h ago)")
                else if (hours == 1L) append(" (calibrated 1h ago)")
                else append(" (calibrated ${hours}h ago)")
            }
        }
        compassQualityText.text = qualityText
        
        // Enable/disable calibration button based on status
        compassCalibrateButton.isEnabled = true
    }

    private fun getCurrentSensorAccuracy(): Int {
        return currentSensorAccuracy
    }

    private fun getCalibrationStatus(accuracy: Int): com.tak.lite.ui.location.CalibrationStatus {
        return when (accuracy) {
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> com.tak.lite.ui.location.CalibrationStatus.EXCELLENT
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> com.tak.lite.ui.location.CalibrationStatus.GOOD
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> com.tak.lite.ui.location.CalibrationStatus.POOR
            android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> com.tak.lite.ui.location.CalibrationStatus.UNKNOWN
            else -> com.tak.lite.ui.location.CalibrationStatus.UNKNOWN
        }
    }

    private fun getCompassQuality(accuracy: Int): com.tak.lite.ui.location.CompassQuality {
        return when (accuracy) {
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> com.tak.lite.ui.location.CompassQuality.EXCELLENT
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> com.tak.lite.ui.location.CompassQuality.GOOD
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> com.tak.lite.ui.location.CompassQuality.FAIR
            android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE -> com.tak.lite.ui.location.CompassQuality.UNRELIABLE
            else -> com.tak.lite.ui.location.CompassQuality.POOR
        }
    }

    private fun startCompassCalibration() {
        val currentQuality = getCompassQuality(getCurrentSensorAccuracy())
        val needsCalibration = getCalibrationStatus(getCurrentSensorAccuracy()) == com.tak.lite.ui.location.CalibrationStatus.UNKNOWN
        
        val intent = com.tak.lite.ui.location.CompassCalibrationActivity.createIntent(
            this,
            currentQuality,
            needsCalibration
        )
        startActivityForResult(intent, REQUEST_CODE_COMPASS_CALIBRATION)
    }

    override fun onPause() {
        super.onPause()
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val value = minLineSegmentDistEditText.text.toString().toFloatOrNull()
        if (value != null) {
            prefs.edit().putFloat("min_line_segment_dist_miles", value).apply()
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return BLUETOOTH_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestBluetoothPermissions() {
        ActivityCompat.requestPermissions(this, BLUETOOTH_PERMISSIONS, REQUEST_CODE_BLUETOOTH_PERMISSIONS)
    }

    private fun updateBluetoothButtonState() {
        // Use current protocol reference
        val protocol = currentProtocol
        val isConnected = protocol.connectionState.value is MeshConnectionState.Connected
        val buttonText = if (isConnected) "Disconnect" else "Connect to Meshtastic via Bluetooth"
        
        Log.d("SettingsActivity", "updateBluetoothButtonState - protocol: ${protocol.javaClass.simpleName}, isConnected: $isConnected, buttonText: $buttonText")
        
        bluetoothConnectButton.text = buttonText
        // Ensure button is enabled when connection state changes
        bluetoothConnectButton.isEnabled = true
    }

    private fun showDeviceScanDialog(protocol: MeshProtocol) {
        Log.d("SettingsActivity", "Connect button clicked - using protocol: ${protocol.javaClass.simpleName}")
        val discoveredDevices = mutableListOf<com.tak.lite.di.DeviceInfo>()
        val deviceNames = mutableListOf<String>()

        val progressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Scanning for devices...")
            .setView(android.widget.ProgressBar(this))
            .setCancelable(true)
            .setOnCancelListener {
                bluetoothConnectButton.isEnabled = true
            }
            .create()
        progressDialog.show()

        protocol.scanForDevices(onResult = { deviceInfo ->
            discoveredDevices.add(deviceInfo)
            deviceNames.add("${deviceInfo.name} (${deviceInfo.address})")
        }, onScanFinished = {
            progressDialog.dismiss()
            bluetoothConnectButton.isEnabled = true // Re-enable button when scan finishes
            if (deviceNames.isEmpty()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("No devices found")
                    .setMessage("No compatible devices were found. Make sure your device is powered on and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Select Device")
                    .setItems(deviceNames.toTypedArray()) { _, which ->
                        val deviceInfo = discoveredDevices[which]
                        bluetoothStatusText.text = "Connecting to: ${deviceInfo.name} (${deviceInfo.address})..."
                        protocol.connectToDevice(deviceInfo) { _ ->
                            // UI will update via state observer
                        }
                    }
                    .setCancelable(true)
                    .show()
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_BLUETOOTH_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                showDeviceScanDialog(currentProtocol)
            } else {
                bluetoothStatusText.text = "Bluetooth permissions are required to connect."
            }
        } else if (requestCode == REQUEST_CODE_ALL_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                android.widget.Toast.makeText(this, "All permissions are required to enable background processing.", android.widget.Toast.LENGTH_LONG).show()
                backgroundProcessingSwitch.isChecked = false
            }
        } else if (requestCode == REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                android.widget.Toast.makeText(this, "Background processing permission denied.", android.widget.Toast.LENGTH_LONG).show()
                backgroundProcessingSwitch.isChecked = false
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_CODE_COMPASS_CALIBRATION -> {
                if (resultCode == RESULT_OK) {
                    // User completed calibration - update the status immediately
                    updateCompassStatus()
                    
                    // Show success message
                    val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
                    val calibrationQuality = prefs.getFloat("calibration_quality", 0f)
                    val osCalibrationTriggered = prefs.getBoolean("os_calibration_triggered", false)
                    
                    val qualityText = when {
                        calibrationQuality >= 0.8f -> "excellent"
                        calibrationQuality >= 0.6f -> "good"
                        calibrationQuality >= 0.4f -> "fair"
                        else -> "poor"
                    }
                    
                    val message = getString(R.string.compass_calibration_completed, qualityText)
                    if (osCalibrationTriggered) {
                        android.widget.Toast.makeText(this, "$message ${getString(R.string.compass_calibration_os_applied)}", android.widget.Toast.LENGTH_LONG).show()
                    } else {
                        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                    }
                } else if (resultCode == RESULT_CANCELED) {
                    android.widget.Toast.makeText(this, getString(R.string.compass_calibration_cancelled), android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            2001 -> { // Bluetooth enable request
                if (isBluetoothEnabled()) {
                    // Optionally, retry connection or inform user
                    bluetoothStatusText.text = "Bluetooth enabled. You can now connect."
                } else {
                    bluetoothStatusText.text = "Bluetooth must be enabled to connect."
                }
            }
        }
    }

    private fun applyDarkMode(mode: String) {
        val nightMode = when (mode) {
            "dark" -> AppCompatDelegate.MODE_NIGHT_YES
            "light" -> AppCompatDelegate.MODE_NIGHT_NO
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(nightMode)
    }

    override fun onResume() {
        super.onResume()
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val mode = prefs.getString("dark_mode", "system") ?: "system"
        applyDarkMode(mode)
        // Ensure keep screen awake is always set according to preference
        val keepAwakeEnabled = prefs.getBoolean("keep_screen_awake", false)
        setKeepScreenAwake(keepAwakeEnabled)
    }

    private fun setKeepScreenAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as android.bluetooth.BluetoothManager
        val adapter = bluetoothManager.adapter
        return adapter != null && adapter.isEnabled
    }

    private fun isLocationEnabled(): Boolean {
        val locationManager = getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun promptEnableBluetooth() {
        val enableBtIntent = Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, 2001)
    }

    private fun promptEnableLocation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Enable Location")
            .setMessage("Location services are required to scan for Bluetooth devices. Please enable location.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun promptDisableBatteryOptimizationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            val packageName = packageName
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }
    }

    private fun updateConfigProgressText(step: ConfigDownloadStep) {
        val counters = currentProtocol.configStepCounters.value
        configProgressText.text = when (step) {
            is ConfigDownloadStep.SendingHandshake -> "Sending handshake..."
            is ConfigDownloadStep.WaitingForConfig -> "Waiting for config..."
            is ConfigDownloadStep.DownloadingConfig -> {
                val count = counters[step] ?: 0
                "Downloading: Device Config... (${count})"
            }
            is ConfigDownloadStep.DownloadingModuleConfig -> {
                val count = counters[step] ?: 0
                "Downloading: Module Config... (${count})"
            }
            is ConfigDownloadStep.DownloadingChannel -> {
                val count = counters[step] ?: 0
                "Downloading: Channel Info... (${count})"
            }
            is ConfigDownloadStep.DownloadingNodeInfo -> {
                val count = counters[step] ?: 0
                "Downloading: Node Info... (${count})"
            }
            is ConfigDownloadStep.DownloadingMyInfo -> {
                val count = counters[step] ?: 0
                "Downloading: My Info... (${count})"
            }
            else -> "Downloading config..."
        }
    }

    private fun updateMeshSettingsVisibility(isEnabled: Boolean) {
        if (isEnabled) {
            meshNetworkTypeLayout.visibility = View.VISIBLE
            unlockAppButton.visibility = View.GONE
            // The specific connect buttons and status texts will be shown/hidden by updateConnectionUIForMeshType
        } else {
            meshNetworkTypeLayout.visibility = View.GONE
            bluetoothConnectButton.visibility = View.GONE
            bluetoothStatusText.visibility = View.GONE
            aidlConnectButton.visibility = View.GONE
            aidlStatusText.visibility = View.GONE
            unlockAppButton.visibility = View.VISIBLE
        }
    }

    private fun showPurchaseDialog() {
        val dialog = com.tak.lite.ui.PurchaseDialog()
        dialog.show(supportFragmentManager, "purchase_dialog")
    }

    private fun showPredictionAdvancedSettings() {
        val dialog = PredictionAdvancedSettingsDialog()
        dialog.show(supportFragmentManager, "prediction_advanced_settings")
    }

    private fun updateConnectionUIForMeshType(meshType: String) {
        Log.d("SettingsActivity", "updateConnectionUIForMeshType - meshType: $meshType")
        
        when (meshType) {
            "Meshtastic" -> {
                bluetoothConnectButton.visibility = View.VISIBLE
                bluetoothStatusText.visibility = View.VISIBLE
                aidlConnectButton.visibility = View.GONE
                aidlStatusText.visibility = View.GONE
                Log.d("SettingsActivity", "Showing Bluetooth UI, hiding AIDL UI")
            }
            "MeshtasticAidl" -> {
                bluetoothConnectButton.visibility = View.GONE
                bluetoothStatusText.visibility = View.GONE
                aidlConnectButton.visibility = View.VISIBLE
                aidlStatusText.visibility = View.VISIBLE
                Log.d("SettingsActivity", "Showing AIDL UI, hiding Bluetooth UI")
            }
            else -> {
                bluetoothConnectButton.visibility = View.GONE
                bluetoothStatusText.visibility = View.GONE
                aidlConnectButton.visibility = View.GONE
                aidlStatusText.visibility = View.GONE
                Log.d("SettingsActivity", "Hiding all connection UI")
            }
        }
    }

    private fun updateConnectionStatus(state: MeshConnectionState) {
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        val currentMeshType = prefs.getString("mesh_network_type", "Meshtastic")
        
        Log.d("SettingsActivity", "updateConnectionStatus called - state: $state, meshType: $currentMeshType, protocol: ${currentProtocol.javaClass.simpleName}")
        
        when (state) {
            is MeshConnectionState.Connected -> {
                val deviceName = state.deviceInfo?.name ?: "device"
                val connectionType = state.deviceInfo?.connectionType
                
                Log.d("SettingsActivity", "Connected state - deviceName: $deviceName, connectionType: $connectionType")
                
                if (connectionType == "bluetooth" || currentMeshType == "Meshtastic") {
                    bluetoothStatusText.text = "Connected: $deviceName"
                    updateBluetoothButtonState()
                    Log.d("SettingsActivity", "Updated Bluetooth status: Connected: $deviceName")
                } else if (connectionType == "aidl" || currentMeshType == "MeshtasticAidl") {
                    // For AIDL, try to get user information
                    val userInfo = currentProtocol.getLocalUserInfo()
                    
                    Log.d("SettingsActivity", "AIDL connection - userInfo: $userInfo")
                    
                    val statusText = if (userInfo != null) {
                        val (shortname, hwmodel) = userInfo
                        "Connected: $deviceName ($shortname - $hwmodel)"
                    } else {
                        "Connected: $deviceName"
                    }
                    
                    aidlStatusText.text = statusText
                    updateAidlButtonState()
                    Log.d("SettingsActivity", "Updated AIDL status: $statusText")
                }
            }
            is MeshConnectionState.Disconnected -> {
                connectedDevice = null
                Log.d("SettingsActivity", "Disconnected state")
                
                if (currentMeshType == "Meshtastic") {
                    bluetoothStatusText.text = "Not connected"
                    updateBluetoothButtonState()
                    Log.d("SettingsActivity", "Updated Bluetooth status: Not connected")
                } else if (currentMeshType == "MeshtasticAidl") {
                    aidlStatusText.text = "Not connected"
                    updateAidlButtonState()
                    Log.d("SettingsActivity", "Updated AIDL status: Not connected")
                }
            }
            is MeshConnectionState.Error -> {
                connectedDevice = null
                val errorMessage = if (state.message.contains("Meshtastic")) {
                    // Provide more helpful error messages for AIDL issues
                    when {
                        state.message.contains("not installed") -> "Please install Meshtastic app from Play Store"
                        state.message.contains("service may not be running") -> "Please open Meshtastic app and keep it running"
                        state.message.contains("service not found") -> "Meshtastic app version may not support AIDL"
                        else -> state.message
                    }
                } else {
                    state.message
                }
                
                Log.d("SettingsActivity", "Error state - message: $errorMessage")
                
                if (currentMeshType == "Meshtastic") {
                    bluetoothStatusText.text = "Connection failed: $errorMessage"
                    updateBluetoothButtonState()
                    Log.d("SettingsActivity", "Updated Bluetooth status: Connection failed: $errorMessage")
                } else if (currentMeshType == "MeshtasticAidl") {
                    aidlStatusText.text = "Connection failed: $errorMessage"
                    updateAidlButtonState()
                    Log.d("SettingsActivity", "Updated AIDL status: Connection failed: $errorMessage")
                }
            }
            MeshConnectionState.Connecting -> {
                Log.d("SettingsActivity", "Connecting state")
                
                if (currentMeshType == "Meshtastic") {
                    bluetoothStatusText.text = "Connecting..."
                    Log.d("SettingsActivity", "Updated Bluetooth status: Connecting...")
                } else if (currentMeshType == "MeshtasticAidl") {
                    aidlStatusText.text = "Connecting..."
                    Log.d("SettingsActivity", "Updated AIDL status: Connecting...")
                }
            }
        }
    }

    private fun updateAidlButtonState() {
        // Use current protocol reference
        val protocol = currentProtocol
        val isConnected = protocol.connectionState.value is MeshConnectionState.Connected
        val buttonText = if (isConnected) "Disconnect" else "Connect"
        
        Log.d("SettingsActivity", "updateAidlButtonState - protocol: ${protocol.javaClass.simpleName}, isConnected: $isConnected, buttonText: $buttonText")
        Log.d("SettingsActivity", "updateAidlButtonState - connectionState: ${protocol.connectionState.value}")
        
        aidlConnectButton.text = buttonText
        // Ensure button is enabled when connection state changes
        aidlConnectButton.isEnabled = true
        
        Log.d("SettingsActivity", "AIDL button updated - text: ${aidlConnectButton.text}, enabled: ${aidlConnectButton.isEnabled}")
    }

    private fun startProtocolObservers(protocol: MeshProtocol) {
        Log.d("SettingsActivity", "Starting protocol observers for: ${protocol.javaClass.simpleName}")
        Log.d("SettingsActivity", "Current connection state: ${protocol.connectionState.value}")
        
        // Observe connection state changes
        connectionStateJob = lifecycleScope.launch {
            Log.d("SettingsActivity", "Starting connection state observer for: ${protocol.javaClass.simpleName}")
            protocol.connectionState.collect { state ->
                Log.d("SettingsActivity", "Connection state changed: $state for protocol: ${protocol.javaClass.simpleName}")
                Log.d("SettingsActivity", "Current protocol reference: ${currentProtocol.javaClass.simpleName}")
                updateConnectionStatus(state)
                
                // Also handle config progress visibility based on connection state
                when (state) {
                    is MeshConnectionState.Disconnected -> {
                        // Hide handshake progress when disconnected
                        configProgressBar.visibility = View.GONE
                        configProgressText.visibility = View.GONE
                    }
                    else -> {
                        // For other states, let the handshake step observer handle visibility
                        // This will be handled by the stepFlow observer below
                    }
                }
            }
        }
        
        // Observe config download step changes
        protocol.configDownloadStep?.let { stepFlow ->
            configStepJob = lifecycleScope.launch {
                stepFlow.collect { step ->
                    // Check if we're disconnected - if so, hide handshake progress regardless of step
                    val isDisconnected = protocol.connectionState.value is MeshConnectionState.Disconnected
                    if (isDisconnected) {
                        configProgressBar.visibility = View.GONE
                        configProgressText.visibility = View.GONE
                    } else {
                        when (step) {
                            is ConfigDownloadStep.NotStarted,
                            is ConfigDownloadStep.Complete -> {
                                configProgressBar.visibility = View.GONE
                                configProgressText.visibility = View.GONE
                            }
                            is ConfigDownloadStep.Error -> {
                                configProgressBar.visibility = View.GONE
                                configProgressText.visibility = View.VISIBLE
                                configProgressText.text = "Error: ${step.message}"
                            }
                            else -> {
                                configProgressBar.visibility = View.VISIBLE
                                configProgressText.visibility = View.VISIBLE
                                updateConfigProgressText(step)
                            }
                        }
                    }
                }
            }
        }
        
        // Observe counter updates
        configCounterJob = lifecycleScope.launch {
            protocol.configStepCounters.collect { _ ->
                // Only update if we're in a downloading state and connected
                val currentStep = protocol.configDownloadStep?.value
                val isConnected = protocol.connectionState.value !is MeshConnectionState.Disconnected
                if (isConnected && (currentStep is ConfigDownloadStep.DownloadingConfig ||
                    currentStep is ConfigDownloadStep.DownloadingModuleConfig ||
                    currentStep is ConfigDownloadStep.DownloadingChannel ||
                    currentStep is ConfigDownloadStep.DownloadingNodeInfo ||
                    currentStep is ConfigDownloadStep.DownloadingMyInfo)) {
                    updateConfigProgressText(currentStep)
                }
            }
        }
    }

    private fun checkMeshtasticAppInstallation() {
        val protocol = currentProtocol
        if (protocol is com.tak.lite.network.MeshtasticAidlProtocol) {
            val status = protocol.checkMeshtasticAppStatus()
            Log.d("SettingsActivity", "Meshtastic app status: $status")
            
            val isMeshtasticInstalled = try {
                packageManager.getPackageInfo("com.geeksville.mesh", 0)
                true
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error checking Meshtastic app: ${e.message}")
                false
            }
            
            if (!isMeshtasticInstalled) {
                // Show dialog to install Meshtastic
                android.app.AlertDialog.Builder(this)
                    .setTitle("Meshtastic App Required")
                    .setMessage("The Meshtastic AIDL protocol requires the Meshtastic app to be installed. Would you like to install it from the Play Store?")
                    .setPositiveButton("Install") { _, _ ->
                        try {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("market://details?id=com.geeksville.mesh")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            // Fallback to web browser
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.geeksville.mesh")
                            }
                            startActivity(intent)
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            } else {
                // App is installed, show status
                Log.i("SettingsActivity", "Meshtastic app is installed: $status")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Cancel all protocol observation jobs
        protocolJob?.cancel()
        connectionStateJob?.cancel()
        configStepJob?.cancel()
        configCounterJob?.cancel()
        
        // Unregister sensor listener to prevent memory leaks
        sensorManager.unregisterListener(sensorListener)
    }
}