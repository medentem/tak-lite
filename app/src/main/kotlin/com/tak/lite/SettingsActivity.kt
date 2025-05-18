package com.tak.lite

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.tak.lite.ui.map.MapController

class SettingsActivity : AppCompatActivity() {
    private lateinit var mapModeSpinner: AutoCompleteTextView
    private lateinit var endBeepSwitch: SwitchMaterial
    private lateinit var minLineSegmentDistEditText: com.google.android.material.textfield.TextInputEditText
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