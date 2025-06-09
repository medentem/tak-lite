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
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tak.lite.network.BluetoothDeviceManager
import com.tak.lite.service.MeshForegroundService
import com.tak.lite.ui.map.MapController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : BaseActivity() {
    @Inject lateinit var meshProtocolProvider: com.tak.lite.network.MeshProtocolProvider
    private lateinit var mapModeSpinner: AutoCompleteTextView
    private lateinit var endBeepSwitch: SwitchMaterial
    private lateinit var minLineSegmentDistEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var bluetoothConnectButton: Button
    private lateinit var bluetoothStatusText: TextView
    private lateinit var darkModeSpinner: AutoCompleteTextView
    private lateinit var keepScreenAwakeSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var simulatePeersSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var simulatedPeersCountEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var simulatedPeersCountLayout: com.google.android.material.textfield.TextInputLayout
    private var connectedDevice: BluetoothDevice? = null
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
    private var isBluetoothConnected: Boolean = false
    private lateinit var configProgressBar: android.widget.ProgressBar
    private lateinit var configProgressText: android.widget.TextView
    private lateinit var backgroundProcessingSwitch: com.google.android.material.switchmaterial.SwitchMaterial
    private val REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE = 2003
    private val REQUEST_CODE_NOTIFICATION_PERMISSION = 3001
    private val REQUEST_CODE_ALL_PERMISSIONS = 4001

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
        darkModeSpinner = findViewById(R.id.darkModeSpinner)
        keepScreenAwakeSwitch = findViewById(R.id.keepScreenAwakeSwitch)
        simulatePeersSwitch = findViewById(R.id.simulatePeersSwitch)
        simulatedPeersCountEditText = findViewById(R.id.simulatedPeersCountEditText)
        simulatedPeersCountLayout = findViewById(R.id.simulatedPeersCountLayout)
        configProgressBar = findViewById(R.id.configProgressBar)
        configProgressText = findViewById(R.id.configProgressText)
        backgroundProcessingSwitch = findViewById(R.id.backgroundProcessingSwitch)

        // Setup map mode spinner
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, mapModeOptions)
        mapModeSpinner.setAdapter(adapter)

        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
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

        updateBluetoothButtonState()

        val bluetoothDeviceManager = meshProtocolProvider.getBluetoothDeviceManager()

        bluetoothConnectButton.setOnClickListener {
            if (isBluetoothConnected) {
                bluetoothDeviceManager.disconnect()
            } else {
                when {
                    !isBluetoothEnabled() -> {
                        promptEnableBluetooth()
                    }
                    !isLocationEnabled() -> {
                        promptEnableLocation()
                    }
                    !hasBluetoothPermissions() -> {
                        requestBluetoothPermissions()
                    }
                    else -> {
                        showBluetoothScanDialog(bluetoothDeviceManager)
                    }
                }
            }
        }

        // Setup mesh network adapter spinner
        val meshNetworkTypeSpinner = findViewById<com.google.android.material.textfield.MaterialAutoCompleteTextView>(R.id.meshNetworkTypeSpinner)
        val meshNetworkOptions = listOf("Layer 2", "Meshtastic")
        val meshAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, meshNetworkOptions)
        meshNetworkTypeSpinner.setAdapter(meshAdapter)
        val savedMeshType = prefs.getString("mesh_network_type", meshNetworkOptions[0])
        meshNetworkTypeSpinner.setText(savedMeshType, false)

        // Show/hide the connect button based on initial value
        bluetoothConnectButton.visibility = if (savedMeshType == "Meshtastic") android.view.View.VISIBLE else android.view.View.GONE

        meshNetworkTypeSpinner.setOnItemClickListener { _, _, position, _ ->
            val selectedType = meshNetworkOptions[position]
            prefs.edit().putString("mesh_network_type", selectedType).apply()
            // Show/hide the connect button based on selection
            bluetoothConnectButton.visibility = if (selectedType == "Meshtastic") android.view.View.VISIBLE else android.view.View.GONE
            if (selectedType != "Meshtastic" && isBluetoothConnected) {
                bluetoothDeviceManager.disconnect()
            }
        }

        // Listen for connection state changes
        bluetoothDeviceManager.connectionState.let { stateFlow ->
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    stateFlow.collect { state ->
                        when (state) {
                            is BluetoothDeviceManager.ConnectionState.Connected -> {
                                isBluetoothConnected = true
                                connectedDevice = state.device
                                bluetoothStatusText.text = "Connected: ${state.device.name ?: "Unknown"} (${state.device.address})"
                                updateBluetoothButtonState()
                            }
                            is BluetoothDeviceManager.ConnectionState.Disconnected -> {
                                isBluetoothConnected = false
                                connectedDevice = null
                                bluetoothStatusText.text = "Not connected"
                                updateBluetoothButtonState()
                            }
                            is BluetoothDeviceManager.ConnectionState.Failed -> {
                                isBluetoothConnected = false
                                connectedDevice = null
                                bluetoothStatusText.text = "Connection failed: ${state.reason}"
                                updateBluetoothButtonState()
                            }
                            BluetoothDeviceManager.ConnectionState.Connecting -> {
                                bluetoothStatusText.text = "Connecting..."
                            }
                        }
                    }
                }
            }
        }

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

        // Observe config download progress if available
        val protocol = meshProtocolProvider.protocol.value
        protocol.configDownloadStep?.let { stepFlow ->
            lifecycleScope.launch {
                repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                    stepFlow.collect { step ->
                        when (step) {
                            is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.NotStarted,
                            is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.Complete -> {
                                configProgressBar.visibility = android.view.View.GONE
                                configProgressText.visibility = android.view.View.GONE
                            }
                            is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.Error -> {
                                configProgressBar.visibility = android.view.View.GONE
                                configProgressText.visibility = android.view.View.VISIBLE
                                configProgressText.text = "Error: ${step.message}"
                            }
                            else -> {
                                configProgressBar.visibility = android.view.View.VISIBLE
                                configProgressText.visibility = android.view.View.VISIBLE
                                configProgressText.text = when (step) {
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.SendingHandshake -> "Sending handshake..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.WaitingForConfig -> "Waiting for config..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.DownloadingConfig -> "Downloading: Device Config..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.DownloadingModuleConfig -> "Downloading: Module Config..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.DownloadingChannel -> "Downloading: Channel Info..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.DownloadingNodeInfo -> "Downloading: Node Info..."
                                    is com.tak.lite.network.MeshtasticBluetoothProtocol.ConfigDownloadStep.DownloadingMyInfo -> "Downloading: My Info..."
                                    else -> "Downloading config..."
                                }
                            }
                        }
                    }
                }
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
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    android.content.Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                stopService(android.content.Intent(this, MeshForegroundService::class.java))
            }
        }

        val showPacketSummarySwitch = findViewById<SwitchMaterial>(R.id.showPacketSummarySwitch)
        val showPacketSummaryEnabled = prefs.getBoolean("show_packet_summary", false)
        showPacketSummarySwitch.isChecked = showPacketSummaryEnabled
        showPacketSummarySwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("show_packet_summary", isChecked).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
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
        bluetoothConnectButton.text = if (isBluetoothConnected) "Disconnect" else "Connect to Meshtastic via Bluetooth"
    }

    private fun showBluetoothScanDialog(bluetoothDeviceManager: BluetoothDeviceManager?) {
        if (bluetoothDeviceManager == null) return
        val discoveredDevices = mutableListOf<BluetoothDevice>()
        val deviceNames = mutableListOf<String>()
        val serviceUuid = UUID.fromString("6ba1b218-15a8-461f-9fa8-5dcae273eafd")

        val progressDialog = android.app.AlertDialog.Builder(this)
            .setTitle("Scanning for Meshtastic devices...")
            .setView(android.widget.ProgressBar(this))
            .setCancelable(true)
            .create()
        progressDialog.show()

        bluetoothDeviceManager.scanForDevices(serviceUuid, onResult = { device ->
            discoveredDevices.add(device)
            val name = device.name ?: "Unknown Meshtastic Device"
            deviceNames.add("$name (${device.address})")
        }, onScanFinished = {
            progressDialog.dismiss()
            if (deviceNames.isEmpty()) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("No devices found")
                    .setMessage("No Meshtastic devices were found. Make sure your device is powered on and try again.")
                    .setPositiveButton("OK", null)
                    .show()
            } else {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Select Meshtastic Device")
                    .setItems(deviceNames.toTypedArray()) { _, which ->
                        val device = discoveredDevices[which]
                        bluetoothStatusText.text = "Connecting to: ${device.name ?: "Unknown"} (${device.address})..."
                        bluetoothDeviceManager.connect(device) { _ ->
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
                showBluetoothScanDialog(null)
            } else {
                bluetoothStatusText.text = "Bluetooth permissions are required to connect."
            }
        } else if (requestCode == REQUEST_CODE_ALL_PERMISSIONS) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    android.content.Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                android.widget.Toast.makeText(this, "All permissions are required to enable background processing.", android.widget.Toast.LENGTH_LONG).show()
                backgroundProcessingSwitch.isChecked = false
            }
        } else if (requestCode == REQUEST_CODE_FOREGROUND_SERVICE_CONNECTED_DEVICE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                androidx.core.content.ContextCompat.startForegroundService(
                    this,
                    android.content.Intent(this, MeshForegroundService::class.java)
                )
                promptDisableBatteryOptimizationsIfNeeded()
            } else {
                android.widget.Toast.makeText(this, "Background processing permission denied.", android.widget.Toast.LENGTH_LONG).show()
                backgroundProcessingSwitch.isChecked = false
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
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
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
        val enableBtIntent = android.content.Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, 2001)
    }

    private fun promptEnableLocation() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Enable Location")
            .setMessage("Location services are required to scan for Bluetooth devices. Please enable location.")
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001) {
            if (isBluetoothEnabled()) {
                // Optionally, retry connection or inform user
                bluetoothStatusText.text = "Bluetooth enabled. You can now connect."
            } else {
                bluetoothStatusText.text = "Bluetooth must be enabled to connect."
            }
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
} 