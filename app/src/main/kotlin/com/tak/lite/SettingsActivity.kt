package com.tak.lite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tak.lite.ui.map.MapController
import com.tak.lite.network.MeshtasticBluetoothProtocol
import android.widget.Button
import android.widget.TextView
import android.bluetooth.BluetoothDevice

class SettingsActivity : AppCompatActivity() {
    private lateinit var mapModeSpinner: AutoCompleteTextView
    private lateinit var endBeepSwitch: SwitchMaterial
    private lateinit var minLineSegmentDistEditText: com.google.android.material.textfield.TextInputEditText
    private lateinit var bluetoothConnectButton: Button
    private lateinit var bluetoothStatusText: TextView
    private lateinit var meshtasticBluetoothProtocol: MeshtasticBluetoothProtocol
    private var connectedDevice: BluetoothDevice? = null
    private val mapModeOptions = listOf("Last Used", "Street", "Satellite", "Hybrid")
    private val mapModeEnumValues = listOf(
        MapController.MapType.LAST_USED,
        MapController.MapType.STREETS,
        MapController.MapType.SATELLITE,
        MapController.MapType.HYBRID
    )

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
        meshtasticBluetoothProtocol = MeshtasticBluetoothProtocol(this)

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

        bluetoothConnectButton.setOnClickListener {
            meshtasticBluetoothProtocol.showScanDialog { device ->
                bluetoothStatusText.text = "Connecting to: ${device.name ?: "Unknown"} (${device.address})..."
                meshtasticBluetoothProtocol.connectToDevice(device) { success ->
                    runOnUiThread {
                        if (success) {
                            bluetoothStatusText.text = "Connected: ${device.name ?: "Unknown"} (${device.address})"
                            prefs.edit().putString("meshtastic_bt_device_name", device.name).putString("meshtastic_bt_device_addr", device.address).apply()
                            connectedDevice = device
                        } else {
                            bluetoothStatusText.text = "Failed to connect to: ${device.name ?: "Unknown"} (${device.address})"
                        }
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
} 