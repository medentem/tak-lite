package com.tak.lite

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tak.lite.network.BluetoothDeviceManager
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.ui.map.MapController
import kotlinx.coroutines.launch
import java.util.UUID
import androidx.appcompat.app.AppCompatDelegate

class SettingsActivity : AppCompatActivity() {
    private lateinit var mapModeSpinner: AutoCompleteTextView
    private lateinit var endBeepSwitch: SwitchMaterial
    private lateinit var minLineSegmentDistEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var bluetoothConnectButton: Button
    private lateinit var bluetoothStatusText: TextView
    private lateinit var darkModeSpinner: AutoCompleteTextView
    private lateinit var keepScreenAwakeSwitch: com.google.android.material.switchmaterial.SwitchMaterial
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

        val bluetoothDeviceManager = MeshProtocolProvider.getBluetoothDeviceManager()

        bluetoothConnectButton.setOnClickListener {
            if (isBluetoothConnected) {
                // Disconnect
                bluetoothDeviceManager?.disconnect()
                connectedDevice = null
                isBluetoothConnected = false
                bluetoothStatusText.text = "Not connected"
                updateBluetoothButtonState()
            } else {
                if (hasBluetoothPermissions()) {
                    showBluetoothScanDialog(bluetoothDeviceManager)
                } else {
                    requestBluetoothPermissions()
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
                bluetoothDeviceManager?.disconnect()
                connectedDevice = null
                isBluetoothConnected = false
                bluetoothStatusText.text = "Not connected"
                updateBluetoothButtonState()
            }
        }

        // Listen for connection state changes
        bluetoothDeviceManager?.connectionState?.let { stateFlow ->
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
    }

    private fun setKeepScreenAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
} 