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
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
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
import com.tak.lite.repository.WeatherRepository
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.map.MapController
import com.tak.lite.ui.settings.PredictionAdvancedSettingsDialog
import com.tak.lite.ui.util.EdgeToEdgeHelper
import com.tak.lite.util.BillingManager
import com.tak.lite.util.LocaleManager
import com.tak.lite.util.UnitManager
import com.tak.lite.network.ServerApiService
import com.tak.lite.network.SocketService
import com.tak.lite.network.HybridSyncManager
import com.tak.lite.data.model.Team
import com.tak.lite.repository.AnnotationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.widget.Toast

@AndroidEntryPoint
class SettingsActivity : BaseActivity() {
    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider
    @Inject lateinit var billingManager: BillingManager
    @Inject lateinit var annotationRepository: AnnotationRepository
    @Inject lateinit var weatherRepository: WeatherRepository
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
    private lateinit var simulatedPeersCentralTendencyEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var simulatedPeersCentralTendencyLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var meshNetworkTypeLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var unlockAppButton: com.google.android.material.button.MaterialButton
    private var connectedDevice: BluetoothDevice? = null
    
    private lateinit var currentProtocol: com.tak.lite.di.MeshProtocol
    
    private val mapModeEnumValues = listOf(
        MapController.MapType.LAST_USED,
        MapController.MapType.STREETS,
        MapController.MapType.SATELLITE,
        MapController.MapType.HYBRID
    )
    private val darkModeValues = listOf("system", "dark", "light")
    private val weatherSourceValues = listOf("precipitation_new", "clouds_new", "wind_new", "temp_new")
    
    // These will be initialized in onCreate()
    private lateinit var mapModeOptions: List<String>
    private lateinit var darkModeOptions: List<String>
    private lateinit var weatherSourceOptions: List<String>
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
    
    // Activity result launchers
    private val compassCalibrationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // Check if activity is still valid
        if (isFinishing || isDestroyed) {
            Log.w("SettingsActivity", "Activity is finishing or destroyed, skipping activity result handling")
            return@registerForActivityResult
        }
        
        if (result.resultCode == RESULT_OK) {
            // User completed calibration - update the status immediately
            updateCompassStatus()
            
            // Show success message
            val prefs = getSharedPreferences("compass_calibration", MODE_PRIVATE)
            val calibrationQuality = prefs.getFloat("calibration_quality", 0f)
            val osCalibrationTriggered = prefs.getBoolean("os_calibration_triggered", false)
            
            val qualityText = when {
                calibrationQuality >= 0.8f -> getString(R.string.compass_calibration_excellent)
                calibrationQuality >= 0.6f -> getString(R.string.compass_calibration_good)
                calibrationQuality >= 0.4f -> getString(R.string.compass_calibration_fair)
                else -> getString(R.string.compass_calibration_poor)
            }
            
            val message = getString(R.string.compass_calibration_completed, qualityText)
            try {
                if (osCalibrationTriggered) {
                    android.widget.Toast.makeText(this, "$message ${getString(R.string.compass_calibration_os_applied)}", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error showing compass calibration success toast: ${e.message}", e)
            }
        } else if (result.resultCode == RESULT_CANCELED) {
            try {
                android.widget.Toast.makeText(this, getString(R.string.compass_calibration_cancelled), android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error showing compass calibration cancelled toast: ${e.message}", e)
            }
        }
    }
    
    private val bluetoothEnableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (isBluetoothEnabled()) {
            // Optionally, retry connection or inform user
            bluetoothStatusText.text = "Bluetooth enabled. You can now connect."
        } else {
            bluetoothStatusText.text = "Bluetooth must be enabled to connect."
        }
    }
    
    private lateinit var configProgressBar: android.widget.ProgressBar
    private lateinit var configProgressText: TextView
    private lateinit var backgroundProcessingSwitch: SwitchMaterial
    private lateinit var compassStatusText: TextView
    private lateinit var compassQualityText: TextView
    private lateinit var compassCalibrateButton: com.google.android.material.button.MaterialButton
    private lateinit var showPredictionOverlaySwitch: SwitchMaterial
    private lateinit var predictionAdvancedLink: TextView
    private lateinit var peerStalenessThresholdEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var includePeersInCoverageSwitch: SwitchMaterial
    private lateinit var coverageDetailLevelGroup: RadioGroup
    private lateinit var coverageDetailLow: RadioButton
    private lateinit var coverageDetailMedium: RadioButton
    private lateinit var coverageDetailHigh: RadioButton
    private lateinit var userAntennaHeightEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var receivingAntennaHeightEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var weatherSettingsCard: com.google.android.material.card.MaterialCardView
    private lateinit var weatherEnabledSwitch: SwitchMaterial
    private lateinit var weatherSourceSpinner: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var weatherOpacitySlider: com.google.android.material.slider.Slider
    private lateinit var weatherOpacityValue: TextView
    private lateinit var languageSpinner: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var unitSystemSpinner: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var minLineSegmentDistLabel: TextView
    private lateinit var minLineSegmentDistLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var userAntennaHeightLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var receivingAntennaHeightLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var quickMessage1EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var quickMessage2EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var quickMessage3EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var quickMessage4EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var quickMessage5EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var quickMessage6EditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var resetQuickMessagesButton: com.google.android.material.button.MaterialButton
    
    // Server connection properties
    private lateinit var useTakliteServerSwitch: SwitchMaterial
    private lateinit var serverConnectionSettings: LinearLayout
    private lateinit var serverUrlEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var serverUsernameEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var serverPasswordEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var serverConnectButton: com.google.android.material.button.MaterialButton
    private lateinit var serverTeamSpinner: com.google.android.material.textfield.MaterialAutoCompleteTextView
    private lateinit var serverTeamLayout: com.google.android.material.textfield.TextInputLayout
    private lateinit var serverStatusText: TextView
    
    // Server API service
    private lateinit var serverApiService: ServerApiService
    private lateinit var socketService: SocketService
    private lateinit var hybridSyncManager: HybridSyncManager
    
    private val REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE = 2003
    private val REQUEST_CODE_ALL_PERMISSIONS = 4001
    
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

        // Initialize string options that require context
        mapModeOptions = listOf(getString(R.string.last_used), getString(R.string.street), getString(R.string.satellite), getString(R.string.hybrid))
        darkModeOptions = listOf(getString(R.string.use_phone_setting), getString(R.string.always_dark), getString(R.string.always_light))
        weatherSourceOptions = listOf(getString(R.string.precipitation), getString(R.string.clouds), getString(R.string.wind_speed), getString(R.string.temperature))

        val toolbar = findViewById<MaterialToolbar>(R.id.settingsToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        title = getString(R.string.settings)
        // Apply top insets to toolbar for edge-to-edge
        EdgeToEdgeHelper.applyTopInsets(toolbar)

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
        simulatedPeersCentralTendencyEditText = findViewById(R.id.simulatedPeersCentralTendencyEditText)
        simulatedPeersCentralTendencyLayout = findViewById(R.id.simulatedPeersCentralTendencyLayout)
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
        peerStalenessThresholdEditText = findViewById(R.id.peerStalenessThresholdEditText)
        includePeersInCoverageSwitch = findViewById(R.id.includePeersInCoverageSwitch)
        coverageDetailLevelGroup = findViewById(R.id.coverageDetailLevelGroup)
        coverageDetailLow = findViewById(R.id.coverageDetailLow)
        coverageDetailMedium = findViewById(R.id.coverageDetailMedium)
        coverageDetailHigh = findViewById(R.id.coverageDetailHigh)
        userAntennaHeightEditText = findViewById(R.id.userAntennaHeightEditText)
        receivingAntennaHeightEditText = findViewById(R.id.receivingAntennaHeightEditText)
        weatherSettingsCard = findViewById(R.id.weatherSettingsCard)
        weatherEnabledSwitch = findViewById(R.id.weatherEnabledSwitch)
        weatherSourceSpinner = findViewById(R.id.weatherSourceSpinner)
        weatherOpacitySlider = findViewById(R.id.weatherOpacitySlider)
        weatherOpacityValue = findViewById(R.id.weatherOpacityValue)
        languageSpinner = findViewById(R.id.languageSpinner)
        unitSystemSpinner = findViewById(R.id.unitSystemSpinner)
        minLineSegmentDistLabel = findViewById(R.id.minLineSegmentDistLabel)
        minLineSegmentDistLayout = findViewById(R.id.minLineSegmentDistLayout)
        userAntennaHeightLayout = findViewById(R.id.userAntennaHeightLayout)
        receivingAntennaHeightLayout = findViewById(R.id.receivingAntennaHeightLayout)
        quickMessage1EditText = findViewById(R.id.quickMessage1EditText)
        quickMessage2EditText = findViewById(R.id.quickMessage2EditText)
        quickMessage3EditText = findViewById(R.id.quickMessage3EditText)
        quickMessage4EditText = findViewById(R.id.quickMessage4EditText)
        quickMessage5EditText = findViewById(R.id.quickMessage5EditText)
        quickMessage6EditText = findViewById(R.id.quickMessage6EditText)
        resetQuickMessagesButton = findViewById(R.id.resetQuickMessagesButton)
        
        // Server connection properties
        useTakliteServerSwitch = findViewById(R.id.useTakliteServerSwitch)
        serverConnectionSettings = findViewById(R.id.serverConnectionSettings)
        serverUrlEditText = findViewById(R.id.serverUrlEditText)
        serverUsernameEditText = findViewById(R.id.serverUsernameEditText)
        serverPasswordEditText = findViewById(R.id.serverPasswordEditText)
        serverConnectButton = findViewById(R.id.serverConnectButton)
        serverTeamSpinner = findViewById(R.id.serverTeamSpinner)
        serverTeamLayout = findViewById(R.id.serverTeamLayout)
        serverStatusText = findViewById(R.id.serverStatusText)
        
        // Initialize server services
        serverApiService = ServerApiService(this)
        socketService = SocketService(this)
        hybridSyncManager = HybridSyncManager(this, meshProtocolProvider, serverApiService, socketService, annotationRepository)

        // Check premium status and update UI accordingly
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                val isPremium = billingManager.isPremium()
                val inTrial = billingManager.isInTrialPeriod()
                updateMeshSettingsVisibility(isPremium || inTrial)
                updateWeatherSettingsVisibility(isPremium)
            }
        }

        // Setup unlock button
        unlockAppButton.setOnClickListener {
            showAppropriateDialog()
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
        updateMinLineSegmentDistanceDisplay()
        updateCentralTendencyDistanceDisplay()
        updateAntennaHeightDisplays()
        minLineSegmentDistEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = minLineSegmentDistEditText.text.toString().toFloatOrNull()
                if (value != null) {
                    // Convert back to miles for storage
                    val milesValue = UnitManager.displayDistanceToMiles(value.toDouble(), this).toFloat()
                    prefs.edit().putFloat("min_line_segment_dist_miles", milesValue).apply()
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

            if (connectionState is MeshConnectionState.Connected || connectionState is MeshConnectionState.ServiceConnected) {
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
                        .setTitle(getString(R.string.meshtastic_app_not_running))
                        .setMessage(getString(R.string.meshtastic_app_background_required))
                        .setPositiveButton(getString(R.string.ok), null)
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
            if (currentProtocol.connectionState.value is MeshConnectionState.Connected || 
                currentProtocol.connectionState.value is MeshConnectionState.ServiceConnected) {
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
        val showPredictionOverlayEnabled = prefs.getBoolean("show_prediction_overlay", false)
        showPredictionOverlaySwitch.isChecked = showPredictionOverlayEnabled
        showPredictionOverlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_prediction_overlay", isChecked).apply()
        }
        
        // Setup prediction advanced settings link
        predictionAdvancedLink.setOnClickListener {
            showPredictionAdvancedSettings()
        }

        // Load and set peer staleness threshold
        val peerStalenessThreshold = prefs.getInt("peer_staleness_threshold_minutes", 10)
        peerStalenessThresholdEditText.setText(peerStalenessThreshold.toString())
        peerStalenessThresholdEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = peerStalenessThresholdEditText.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 10
                peerStalenessThresholdEditText.setText(value.toString())
                prefs.edit().putInt("peer_staleness_threshold_minutes", value).apply()
            }
        }

        // Setup coverage analysis settings
        val includePeersInCoverage = prefs.getBoolean("include_peers_in_coverage", true)
        includePeersInCoverageSwitch.isChecked = includePeersInCoverage
        includePeersInCoverageSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("include_peers_in_coverage", isChecked).apply()
        }

        // Setup coverage detail level
        val coverageDetailLevel = prefs.getString("coverage_detail_level", "medium") ?: "medium"
        when (coverageDetailLevel) {
            "low" -> coverageDetailLow.isChecked = true
            "medium" -> coverageDetailMedium.isChecked = true
            "high" -> coverageDetailHigh.isChecked = true
        }

        coverageDetailLevelGroup.setOnCheckedChangeListener { _, checkedId ->
            val level = when (checkedId) {
                R.id.coverageDetailLow -> "low"
                R.id.coverageDetailMedium -> "medium"
                R.id.coverageDetailHigh -> "high"
                else -> "medium"
            }
            prefs.edit().putString("coverage_detail_level", level).apply()
            
            // Show warning for medium and high detail levels
            if (level == "medium" || level == "high") {
                val warningMessage = if (level == "high") {
                    "High detail level uses full resolution for your current zoom level, which may consume more battery and take longer to complete."
                } else {
                    "Medium detail level uses 1.5x coarser resolution than high detail, providing balanced performance."
                }
                
                android.app.AlertDialog.Builder(this)
                    .setTitle(getString(R.string.detail_level_warning))
                    .setMessage(warningMessage)
                    .setPositiveButton(getString(R.string.ok), null)
                    .show()
            }
        }

        // Setup antenna heights
        // Load antenna heights - will be set by updateAntennaHeightDisplays()
        userAntennaHeightEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = userAntennaHeightEditText.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 6
                // Convert back to feet for storage
                val feetValue = UnitManager.displayHeightToFeet(value.toDouble(), this).toInt()
                userAntennaHeightEditText.setText(value.toString())
                prefs.edit().putInt("user_antenna_height_feet", feetValue).apply()
            }
        }
        
        receivingAntennaHeightEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = receivingAntennaHeightEditText.text.toString().toIntOrNull()?.coerceIn(1, 100) ?: 6
                // Convert back to feet for storage
                val feetValue = UnitManager.displayHeightToFeet(value.toDouble(), this).toInt()
                receivingAntennaHeightEditText.setText(value.toString())
                prefs.edit().putInt("receiving_antenna_height_feet", feetValue).apply()
            }
        }

        // Setup compass calibration
        setupCompassCalibration()

        // Setup weather settings
        setupWeatherSettings()

        // Setup language selection
        setupLanguageSelection()
        
        // Setup unit system selection
        setupUnitSystemSelection()

        // Setup quick messages settings
        setupQuickMessagesSettings()

        // Setup server connection settings
        setupServerConnectionSettings()

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
        simulatedPeersCentralTendencyLayout.isEnabled = simulatePeersEnabled
        simulatedPeersCentralTendencyEditText.isEnabled = simulatePeersEnabled

        simulatePeersSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("simulate_peers_enabled", isChecked).apply()
            simulatedPeersCountLayout.isEnabled = isChecked
            simulatedPeersCountEditText.isEnabled = isChecked
            simulatedPeersCentralTendencyLayout.isEnabled = isChecked
            simulatedPeersCentralTendencyEditText.isEnabled = isChecked
        }
        simulatedPeersCountEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = simulatedPeersCountEditText.text.toString().toIntOrNull()?.coerceIn(1, 10) ?: 1
                simulatedPeersCountEditText.setText(value.toString())
                prefs.edit().putInt("simulated_peers_count", value).apply()
            }
        }
        simulatedPeersCentralTendencyEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val value = simulatedPeersCentralTendencyEditText.text.toString().toFloatOrNull()?.coerceIn(0.1f, 10.0f) ?: 1.0f
                // Convert back to miles for storage
                val milesValue = UnitManager.displayDistanceToMiles(value.toDouble(), this).toFloat()
                simulatedPeersCentralTendencyEditText.setText(String.format("%.1f", value))
                prefs.edit().putFloat("simulated_peers_central_tendency", milesValue).apply()
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
        compassCalibrationLauncher.launch(intent)
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
        val buttonText = if (isConnected) getString(R.string.disconnect) else getString(R.string.connect_bluetooth)
        
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
            .setTitle(getString(R.string.scanning_for_devices))
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
            // Check if activity is still valid before showing dialogs
            if (isFinishing || isDestroyed) {
                Log.w("SettingsActivity", "Activity is finishing or destroyed, skipping dialog display")
                return@scanForDevices
            }
            
            try {
                progressDialog.dismiss()
                bluetoothConnectButton.isEnabled = true // Re-enable button when scan finishes
                if (deviceNames.isEmpty()) {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.no_devices_found))
                        .setMessage(getString(R.string.no_compatible_devices_found))
                        .setPositiveButton(getString(R.string.ok), null)
                        .show()
                } else {
                    android.app.AlertDialog.Builder(this)
                        .setTitle(getString(R.string.select_device))
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
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error showing scan result dialog: ${e.message}", e)
                // Re-enable button even if dialog fails
                bluetoothConnectButton.isEnabled = true
            }
        })
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        // Check if activity is still valid
        if (isFinishing || isDestroyed) {
            Log.w("SettingsActivity", "Activity is finishing or destroyed, skipping permission result handling")
            return
        }
        
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
                try {
                    android.widget.Toast.makeText(this, getString(R.string.all_permissions_required), android.widget.Toast.LENGTH_LONG).show()
                    backgroundProcessingSwitch.isChecked = false
                } catch (e: Exception) {
                    Log.e("SettingsActivity", "Error showing permission denied toast: ${e.message}", e)
                }
            }
        } else if (requestCode == REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ContextCompat.startForegroundService(
                    this,
                    Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                try {
                    android.widget.Toast.makeText(this, getString(R.string.background_processing_permission_denied), android.widget.Toast.LENGTH_LONG).show()
                    backgroundProcessingSwitch.isChecked = false
                } catch (e: Exception) {
                    Log.e("SettingsActivity", "Error showing permission denied toast: ${e.message}", e)
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
        
        // Refresh language dropdown to ensure it's properly configured
        refreshLanguageDropdown()
    }
    
    private fun refreshLanguageDropdown() {
        // Get available languages with their display names
        val availableLanguages = LocaleManager.getAvailableLanguages(this)
        val languageOptions = availableLanguages.map { it.second }
        
        // Get current language setting
        val currentLanguage = LocaleManager.getLanguage(this)
        val currentLanguageIndex = availableLanguages.indexOfFirst { it.first == currentLanguage }.takeIf { it >= 0 } ?: 0
        
        // Update the adapter
        val languageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageOptions)
        languageSpinner.setAdapter(languageAdapter)
        
        // Set current selection
        languageSpinner.setText(languageOptions[currentLanguageIndex], false)
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
        bluetoothEnableLauncher.launch(enableBtIntent)
    }

    private fun promptEnableLocation() {
        if (isFinishing || isDestroyed) {
            Log.w("SettingsActivity", "Activity is finishing or destroyed, skipping location prompt dialog")
            return
        }
        
        try {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.enable_location))
                .setMessage(getString(R.string.location_services_required))
                .setPositiveButton(getString(R.string.open_settings)) { _, _ ->
                    startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Error showing location prompt dialog: ${e.message}", e)
        }
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

    private fun updateWeatherSettingsVisibility(isEnabled: Boolean) {
        weatherSettingsCard.visibility = if (isEnabled) View.VISIBLE else View.GONE
    }

    private fun setupWeatherSettings() {
        val prefs = applicationContext.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
        
        // Setup weather enabled switch
        val weatherEnabled = prefs.getBoolean("weather_enabled", false)
        weatherEnabledSwitch.isChecked = weatherEnabled
        weatherEnabledSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("weather_enabled", isChecked).apply()
        }
        
        // Setup weather source spinner
        val weatherSourceAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, weatherSourceOptions)
        weatherSourceSpinner.setAdapter(weatherSourceAdapter)
        
        val savedWeatherSource = prefs.getString("weather_source", "precipitation_new") ?: "precipitation_new"
        val selectedWeatherSourceIndex = weatherSourceValues.indexOf(savedWeatherSource).takeIf { it >= 0 } ?: 0
        weatherSourceSpinner.setText(weatherSourceOptions[selectedWeatherSourceIndex], false)
        
        weatherSourceSpinner.setOnItemClickListener { _, _, position, _ ->
            prefs.edit().putString("weather_source", weatherSourceValues[position]).apply()
        }
        
        // Setup weather opacity slider
        val weatherOpacity = prefs.getFloat("weather_opacity", 0.9f)
        weatherOpacitySlider.value = weatherOpacity
        weatherOpacityValue.text = String.format("%.1f", weatherOpacity)
        
        weatherOpacitySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val opacity = value.coerceIn(0.5f, 1.0f)
                weatherOpacityValue.text = String.format("%.1f", opacity)
                prefs.edit().putFloat("weather_opacity", opacity).apply()
            }
        }
    }

    private fun setupLanguageSelection() {
        // Get available languages with their display names
        val availableLanguages = LocaleManager.getAvailableLanguages(this)
        val languageOptions = availableLanguages.map { it.second }
        
        // Setup language spinner with a fresh adapter
        val languageAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, languageOptions)
        languageSpinner.setAdapter(languageAdapter)
        
        // Get current language setting
        val currentLanguage = LocaleManager.getLanguage(this)
        val currentLanguageIndex = availableLanguages.indexOfFirst { it.first == currentLanguage }.takeIf { it >= 0 } ?: 0
        
        // Set current selection
        languageSpinner.setText(languageOptions[currentLanguageIndex], false)
        
        // Setup language selection listener
        languageSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedLanguage = availableLanguages[position].first
            val clickCurrentLanguage = LocaleManager.getLanguage(this)
            
            // Only apply if language actually changed
            if (selectedLanguage != clickCurrentLanguage) {
                LocaleManager.setLanguage(this, selectedLanguage)
                
                // For system language, we need to force a complete recreation
                if (selectedLanguage == LocaleManager.Language.SYSTEM) {
                    // Clear any cached resources and recreate
                    LocaleManager.applyLocaleToResources(this)
                    // Force a complete activity recreation
                    val intent = intent
                    finish()
                    startActivity(intent)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
                    } // No animation
                } else {
                    // Apply locale immediately and recreate activity
                    LocaleManager.applyLocaleAndRecreate(this)
                }
            }
        }
    }

    private fun setupUnitSystemSelection() {
        // Get available unit systems with their display names
        val unitSystemOptions = listOf(
            getString(R.string.use_system_units),
            getString(R.string.imperial_units),
            getString(R.string.metric_units)
        )
        
        // Setup unit system spinner
        val unitSystemAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, unitSystemOptions)
        unitSystemSpinner.setAdapter(unitSystemAdapter)
        
        // Get current unit system setting
        val currentUnitSystem = UnitManager.getUnitSystem(this)
        val currentUnitSystemIndex = when (currentUnitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> 1
            UnitManager.UnitSystem.METRIC -> 2
        }
        
        // Set current selection
        unitSystemSpinner.setText(unitSystemOptions[currentUnitSystemIndex], false)
        
        // Setup unit system selection listener
        unitSystemSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedUnitSystem = when (position) {
                0 -> null // System default
                1 -> UnitManager.UnitSystem.IMPERIAL
                2 -> UnitManager.UnitSystem.METRIC
                else -> null
            }
            
            val clickCurrentUnitSystem = UnitManager.getUnitSystem(this)
            
            // Only apply if unit system actually changed
            if (selectedUnitSystem != clickCurrentUnitSystem) {
                if (selectedUnitSystem != null) {
                    UnitManager.setUnitSystem(this, selectedUnitSystem)
                } else {
                    // Clear preference to use system default
                    val prefs = getSharedPreferences("unit_prefs", Context.MODE_PRIVATE)
                    prefs.edit().remove("unit_system").apply()
                }

                // Update the minimum line segment distance display
                updateMinLineSegmentDistanceDisplay()
                // Update the central tendency distance display
                updateCentralTendencyDistanceDisplay()
                // Update the antenna height displays
                updateAntennaHeightDisplays()
                // Refresh weather data with new units
                weatherRepository.refreshWeatherDataForUnitChange()
            }
        }
    }
    
    private fun updateMinLineSegmentDistanceDisplay() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val milesValue = prefs.getFloat("min_line_segment_dist_miles", 1.0f)
        val unitSystem = UnitManager.getUnitSystem(this)
        
        // Convert to display units
        val displayValue = UnitManager.milesToDisplayDistance(milesValue.toDouble(), this)
        
        // Update the EditText
        minLineSegmentDistEditText.setText(String.format("%.2f", displayValue))
        
        // Update the label
        val labelText = when (unitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> getString(R.string.min_line_segment_dist_imperial)
            UnitManager.UnitSystem.METRIC -> getString(R.string.min_line_segment_dist_metric)
        }
        minLineSegmentDistLabel.text = labelText
        
        // Update the hint
        val hintText = when (unitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> getString(R.string.min_line_segment_dist_hint_imperial)
            UnitManager.UnitSystem.METRIC -> getString(R.string.min_line_segment_dist_hint_metric)
        }
        minLineSegmentDistLayout.hint = hintText
    }
    
    private fun updateCentralTendencyDistanceDisplay() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val milesValue = prefs.getFloat("simulated_peers_central_tendency", 1.0f)
        
        // Convert to display units
        val displayValue = UnitManager.milesToDisplayDistance(milesValue.toDouble(), this)
        
        // Update the EditText
        simulatedPeersCentralTendencyEditText.setText(String.format("%.1f", displayValue))
        
        // Update the hint
        val unitSystem = UnitManager.getUnitSystem(this)
        val hintText = when (unitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> getString(R.string.central_tendency_distance_imperial)
            UnitManager.UnitSystem.METRIC -> getString(R.string.central_tendency_distance_metric)
        }
        simulatedPeersCentralTendencyLayout.hint = hintText
    }
    
    private fun updateAntennaHeightDisplays() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val userAntennaHeightFeet = prefs.getInt("user_antenna_height_feet", 6)
        val receivingAntennaHeightFeet = prefs.getInt("receiving_antenna_height_feet", 6)
        
        // Convert to display units
        val userDisplayHeight = UnitManager.feetToDisplayHeight(userAntennaHeightFeet.toDouble(), this)
        val receivingDisplayHeight = UnitManager.feetToDisplayHeight(receivingAntennaHeightFeet.toDouble(), this)
        
        // Update the EditTexts
        userAntennaHeightEditText.setText(userDisplayHeight.toInt().toString())
        receivingAntennaHeightEditText.setText(receivingDisplayHeight.toInt().toString())
        
        // Update the hints
        val unitSystem = UnitManager.getUnitSystem(this)
        val userHintText = when (unitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> getString(R.string.user_antenna_height_imperial)
            UnitManager.UnitSystem.METRIC -> getString(R.string.user_antenna_height_metric)
        }
        val receivingHintText = when (unitSystem) {
            UnitManager.UnitSystem.IMPERIAL -> getString(R.string.receiving_antenna_height_imperial)
            UnitManager.UnitSystem.METRIC -> getString(R.string.receiving_antenna_height_metric)
        }
        userAntennaHeightLayout.hint = userHintText
        receivingAntennaHeightLayout.hint = receivingHintText
    }

    private fun setupQuickMessagesSettings() {
        // Load current quick messages
        val quickMessages = com.tak.lite.util.QuickMessageManager.getQuickMessages(this)
        
        // Set up EditText fields with current values
        val editTexts = listOf(
            quickMessage1EditText,
            quickMessage2EditText,
            quickMessage3EditText,
            quickMessage4EditText,
            quickMessage5EditText,
            quickMessage6EditText
        )
        
        quickMessages.forEachIndexed { index, message ->
            if (index < editTexts.size) {
                editTexts[index].setText(message.text)
            }
        }
        
        // Set up focus change listeners to save changes
        editTexts.forEachIndexed { index, editText ->
            editText.setOnFocusChangeListener { _, hasFocus ->
                if (!hasFocus) {
                    val newText = editText.text.toString().trim()
                    if (newText.isNotEmpty()) {
                        val updatedMessage = com.tak.lite.data.model.QuickMessage(
                            id = index,
                            text = newText,
                            isDefault = false
                        )
                        com.tak.lite.util.QuickMessageManager.saveQuickMessage(this, updatedMessage)
                    }
                }
            }
        }
        
        // Set up reset button
        resetQuickMessagesButton.setOnClickListener {
            // Show confirmation dialog
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.reset_to_defaults))
                .setMessage("Are you sure you want to reset all quick messages to their default values?")
                .setPositiveButton(getString(R.string.ok)) { _, _ ->
                    // Reset to defaults
                    com.tak.lite.util.QuickMessageManager.resetToDefaults(this)
                    
                    // Reload and update UI
                    val defaultMessages = com.tak.lite.util.QuickMessageManager.getQuickMessages(this)
                    defaultMessages.forEachIndexed { index, message ->
                        if (index < editTexts.size) {
                            editTexts[index].setText(message.text)
                        }
                    }
                    
                    // Show feedback
                    android.widget.Toast.makeText(this, "Quick messages reset to defaults", android.widget.Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.cancel), null)
                .show()
        }
    }

    private fun setupServerConnectionSettings() {
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        
        // Load server connection settings
        val useServer = prefs.getBoolean("use_taklite_server", false)
        val serverUrl = prefs.getString("server_url", "") ?: ""
        val serverUsername = prefs.getString("server_username", "") ?: ""
        val serverPassword = prefs.getString("server_password", "") ?: ""
        val selectedTeam = prefs.getString("selected_team", "") ?: ""
        
        // Setup toggle switch
        useTakliteServerSwitch.isChecked = useServer
        useTakliteServerSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("use_taklite_server", isChecked).apply()
            updateServerConnectionVisibility(isChecked)
        }
        
        // Setup server connection fields
        serverUrlEditText.setText(serverUrl)
        serverUsernameEditText.setText(serverUsername)
        serverPasswordEditText.setText(serverPassword)
        
        // Setup connect button
        serverConnectButton.setOnClickListener {
            val isConnected = serverApiService.isLoggedIn()
            if (isConnected) {
                disconnectFromServer()
            } else {
                connectToServer()
            }
        }
        
        // Setup team selection
        val teamAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mutableListOf<String>())
        serverTeamSpinner.setAdapter(teamAdapter)
        
        // Set initial visibility
        updateServerConnectionVisibility(useServer)
        
        // Load saved values
        if (serverUrl.isNotEmpty()) {
            serverUrlEditText.setText(serverUrl)
        }
        if (serverUsername.isNotEmpty()) {
            serverUsernameEditText.setText(serverUsername)
        }
        if (serverPassword.isNotEmpty()) {
            serverPasswordEditText.setText(serverPassword)
        }
        
        // Setup focus change listeners to save values
        serverUrlEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val url = serverUrlEditText.text.toString().trim()
                prefs.edit().putString("server_url", url).apply()
            }
        }
        
        serverUsernameEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val username = serverUsernameEditText.text.toString().trim()
                prefs.edit().putString("server_username", username).apply()
            }
        }
        
        serverPasswordEditText.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val password = serverPasswordEditText.text.toString().trim()
                prefs.edit().putString("server_password", password).apply()
            }
        }
        
        // Update status
        updateServerConnectionStatus()
    }
    
    private fun updateServerConnectionVisibility(show: Boolean) {
        serverConnectionSettings.visibility = if (show) View.VISIBLE else View.GONE
    }
    
    private fun connectToServer() {
        val url = serverUrlEditText.text.toString().trim()
        val username = serverUsernameEditText.text.toString().trim()
        val password = serverPasswordEditText.text.toString().trim()
        
        // Validate inputs
        if (url.isEmpty()) {
            serverUrlEditText.error = getString(R.string.server_url_validation_error)
            return
        }
        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, getString(R.string.credentials_required), Toast.LENGTH_SHORT).show()
            return
        }
        
        // Update UI to show connecting state
        serverConnectButton.isEnabled = false
        serverConnectButton.text = getString(R.string.server_connecting)
        serverStatusText.text = getString(R.string.server_connecting)
        
        // Perform actual server connection
        lifecycleScope.launch {
            try {
                // Test connection first
                val connectionResult = serverApiService.testConnection(url)
                if (connectionResult.isFailure) {
                    throw Exception("Cannot reach server: ${connectionResult.exceptionOrNull()?.message}")
                }
                
                // Attempt login
                val loginResult = serverApiService.login(url, username, password)
                if (loginResult.isFailure) {
                    throw Exception(loginResult.exceptionOrNull()?.message ?: "Login failed")
                }
                
                // Get user info to verify connection
                val userInfoResult = serverApiService.getUserInfo(url)
                if (userInfoResult.isFailure) {
                    throw Exception("Failed to get user info: ${userInfoResult.exceptionOrNull()?.message}")
                }
                
                // Connect Socket.IO for real-time communication
                val loginResponse = loginResult.getOrThrow()
                socketService.connect(url, loginResponse.token)
                
                // Update UI for successful connection
                serverStatusText.text = getString(R.string.server_connected)
                serverConnectButton.text = getString(R.string.disconnect_from_server)
                serverConnectButton.isEnabled = true
                
                // Show team selection and load teams
                serverTeamLayout.visibility = View.VISIBLE
                loadAvailableTeams()
                
                // Save connection state
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("server_connected", true).apply()
                
                Toast.makeText(this@SettingsActivity, getString(R.string.server_connection_success), Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                // Handle connection error
                serverStatusText.text = getString(R.string.server_connection_failed)
                serverConnectButton.text = getString(R.string.connect_to_server)
                serverConnectButton.isEnabled = true
                
                // Clear any stored connection data
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("server_connected", false).apply()
                serverApiService.clearAllData()
                
                Toast.makeText(this@SettingsActivity, getString(R.string.server_connection_error, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun disconnectFromServer() {
        val serverUrl = serverApiService.getStoredServerUrl()
        if (serverUrl == null) {
            // No server URL stored, just clear local data
            serverApiService.clearAllData()
            updateServerConnectionStatus()
            return
        }
        
        lifecycleScope.launch {
            try {
                // Disable hybrid sync
                hybridSyncManager.disableServerSync()
                
                // Disconnect Socket.IO
                socketService.disconnect()
                
                // Attempt logout from server
                serverApiService.logout(serverUrl)
                
                // Clear local data
                serverApiService.clearAllData()
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("server_connected", false).apply()
                
                // Update UI
                updateServerConnectionStatus()
                
                Toast.makeText(this@SettingsActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                // Even if logout fails, clear local data
                hybridSyncManager.disableServerSync()
                socketService.disconnect()
                serverApiService.clearAllData()
                val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                prefs.edit().putBoolean("server_connected", false).apply()
                updateServerConnectionStatus()
                
                Toast.makeText(this@SettingsActivity, "Disconnected from server", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun loadAvailableTeams() {
        val serverUrl = serverUrlEditText.text.toString().trim()
        if (serverUrl.isEmpty()) {
            Toast.makeText(this, "Server URL is required", Toast.LENGTH_SHORT).show()
            return
        }
        
        lifecycleScope.launch {
            try {
                val teamsResult = serverApiService.getUserTeams(serverUrl)
                if (teamsResult.isFailure) {
                    throw Exception(teamsResult.exceptionOrNull()?.message ?: "Failed to load teams")
                }
                
                val teams = teamsResult.getOrThrow()
                if (teams.isEmpty()) {
                    Toast.makeText(this@SettingsActivity, getString(R.string.no_teams_available), Toast.LENGTH_SHORT).show()
                    return@launch
                }
                
                // Create team names list for the spinner
                val teamNames = teams.map { it.name }
                val teamAdapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_dropdown_item_1line, teamNames)
                serverTeamSpinner.setAdapter(teamAdapter)
                
                // Set up team selection listener
                serverTeamSpinner.setOnItemClickListener { _, _, position, _ ->
                    val selectedTeam = teams[position]
                    val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
                    prefs.edit().putString("selected_team_id", selectedTeam.id).apply()
                    prefs.edit().putString("selected_team_name", selectedTeam.name).apply()
                    
                    // Enable hybrid sync for the selected team
                    hybridSyncManager.enableServerSync(selectedTeam)
                    
                    Toast.makeText(this@SettingsActivity, getString(R.string.team_joined, selectedTeam.name), Toast.LENGTH_SHORT).show()
                }
                
                Toast.makeText(this@SettingsActivity, getString(R.string.teams_loaded), Toast.LENGTH_SHORT).show()
                
            } catch (e: Exception) {
                Toast.makeText(this@SettingsActivity, getString(R.string.team_join_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }
    
    private fun updateServerConnectionStatus() {
        val isLoggedIn = serverApiService.isLoggedIn()
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val isConnected = prefs.getBoolean("server_connected", false) && isLoggedIn
        
        if (isConnected) {
            serverStatusText.text = getString(R.string.server_connected)
            serverConnectButton.text = getString(R.string.disconnect_from_server)
            serverTeamLayout.visibility = View.VISIBLE
            
            // Load teams if we have a server URL
            val serverUrl = serverApiService.getStoredServerUrl()
            if (serverUrl != null) {
                loadAvailableTeams()
            }
        } else {
            serverStatusText.text = getString(R.string.server_not_connected)
            serverConnectButton.text = getString(R.string.connect_to_server)
            serverTeamLayout.visibility = View.GONE
        }
    }

    private fun showAppropriateDialog() {
        // Wait for BillingManager to be fully initialized before making decisions
        lifecycleScope.launch {
            try {
                billingManager.waitForInitialization()
                
                // Check if Google Play Services are available and billing is functional
                val isGooglePlayAvailable = billingManager.isGooglePlayAvailable.value
                val isBillingFunctional = billingManager.isBillingFunctional()
                
                Log.d("SettingsActivity", "showAppropriateDialog: GooglePlay=$isGooglePlayAvailable, BillingFunctional=$isBillingFunctional")
                
                if (isGooglePlayAvailable && isBillingFunctional) {
                    // Show regular purchase dialog for Google Play users with functional billing
                    try {
                        val dialog = com.tak.lite.ui.PurchaseDialog()
                        dialog.show(supportFragmentManager, "purchase_dialog")
                    } catch (e: Exception) {
                        Log.e("SettingsActivity", "Failed to show purchase dialog: ${e.message}")
                        // Fallback to donation dialog
                        showDonationDialogFallback()
                    }
                } else {
                    // Show donation dialog for de-googled users or when billing is not functional
                    showDonationDialogFallback()
                }
            } catch (e: Exception) {
                Log.e("SettingsActivity", "Error in showAppropriateDialog: ${e.message}")
                // Fallback to donation dialog on any error
                showDonationDialogFallback()
            }
        }
    }

    private fun showDonationDialogFallback() {
        try {
            val dialog = com.tak.lite.ui.DonationDialog()
            dialog.show(supportFragmentManager, "donation_dialog")
        } catch (e: Exception) {
            Log.e("SettingsActivity", "Failed to show donation dialog: ${e.message}")
            // Show a simple toast as last resort
            Toast.makeText(this, "Premium features require Google Play Services or donation support", Toast.LENGTH_LONG).show()
        }
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
            is MeshConnectionState.ServiceConnected -> {
                connectedDevice = null
                val deviceName = state.deviceInfo?.name ?: "Unknown Device"
                val connectionType = state.deviceInfo?.connectionType ?: "unknown"
                val currentMeshProtocol = currentProtocol.javaClass.simpleName
                
                Log.d("SettingsActivity", "ServiceConnected state - deviceName: $deviceName, connectionType: $connectionType, currentMeshType: $currentMeshType")
                
                if (connectionType == "aidl" || currentMeshProtocol == "MeshtasticAidl") {
                    aidlStatusText.text = "Meshtastic App Connected: No Device Attached"
                    updateAidlButtonState()
                    Log.d("SettingsActivity", "Updated AIDL status: Meshtastic App Connected: No Device Attached")
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
        val isConnected = protocol.connectionState.value is MeshConnectionState.Connected || 
                         protocol.connectionState.value is MeshConnectionState.ServiceConnected
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
                    is MeshConnectionState.ServiceConnected -> {
                        // Hide handshake progress when service connected but no device attached
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
                    // Check if we're disconnected or service connected - if so, hide handshake progress regardless of step
                    val isDisconnected = protocol.connectionState.value is MeshConnectionState.Disconnected
                    val isServiceConnected = protocol.connectionState.value is MeshConnectionState.ServiceConnected
                    if (isDisconnected || isServiceConnected) {
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
                val isConnected = protocol.connectionState.value !is MeshConnectionState.Disconnected && 
                                protocol.connectionState.value !is MeshConnectionState.ServiceConnected
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
                    .setTitle(getString(R.string.meshtastic_app_required))
                    .setMessage(getString(R.string.meshtastic_app_install_prompt))
                    .setPositiveButton(getString(R.string.install)) { _, _ ->
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
                    .setNegativeButton(getString(R.string.cancel), null)
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