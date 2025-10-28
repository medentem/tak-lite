package com.tak.lite.vuzix

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.tak.lite.R
import com.tak.lite.databinding.FragmentMinimapSettingsBinding
import com.tak.lite.util.UnitManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/**
 * Minimap Settings Fragment for Vuzix Z100 Smart Glasses
 * Provides UI for configuring minimap settings
 */
@AndroidEntryPoint
class MinimapSettingsFragment : Fragment() {

    private var _binding: FragmentMinimapSettingsBinding? = null
    private val binding get() = _binding!!

    private val minimapController: MinimapController by viewModels()
    
    // SharedPreferences for settings persistence (following SettingsActivity pattern)
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMinimapSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        // Initialize SharedPreferences (following SettingsActivity pattern)
        prefs = requireContext().getSharedPreferences("vuzix_minimap_prefs", Context.MODE_PRIVATE)
        
        setupUI()
        loadSettings()
        observeViewModel()
    }

    private fun setupUI() {
        // Minimap toggle
        binding.minimapToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.showMinimap()
            } else {
                minimapController.hideMinimap()
            }
        }

        // Orientation mode
        binding.orientationNorthUp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveOrientation(MinimapOrientation.NORTH_UP)
            }
        }
        binding.orientationHeadingUp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveOrientation(MinimapOrientation.HEADING_UP)
            }
        }
        binding.orientationAuto.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveOrientation(MinimapOrientation.AUTO)
            }
        }

        // Size selection
        binding.sizeSmall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveSize(MinimapSize.SMALL)
            }
        }
        binding.sizeMedium.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveSize(MinimapSize.MEDIUM)
            }
        }
        binding.sizeLarge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                saveSize(MinimapSize.LARGE)
            }
        }

        // Position selection
        binding.positionTopLeft.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savePosition(MinimapPosition.TOP_LEFT)
            }
        }
        binding.positionTopRight.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savePosition(MinimapPosition.TOP_RIGHT)
            }
        }
        binding.positionBottomLeft.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savePosition(MinimapPosition.BOTTOM_LEFT)
            }
        }
        binding.positionBottomRight.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savePosition(MinimapPosition.BOTTOM_RIGHT)
            }
        }
        binding.positionCenter.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                savePosition(MinimapPosition.CENTER)
            }
        }

        // Feature toggles
        binding.featurePeers.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.PEERS, isChecked)
        }
        binding.featureWaypoints.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.WAYPOINTS, isChecked)
        }
        binding.featureGrid.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.GRID, isChecked)
        }
        binding.featureNorthIndicator.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.NORTH_INDICATOR, isChecked)
        }
        binding.featureDistanceRings.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.DISTANCE_RINGS, isChecked)
        }
        binding.featureCompassQuality.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.COMPASS_QUALITY, isChecked)
        }
        binding.featureBatteryLevel.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.BATTERY_LEVEL, isChecked)
        }
        binding.featureNetworkStatus.setOnCheckedChangeListener { _, isChecked ->
            toggleFeature(MinimapFeature.NETWORK_STATUS, isChecked)
        }

        // Zoom control - now represents map radius from 0.25 miles to 50 miles
        binding.zoomSlider.max = 200 // 0.25 to 50 miles (402m to 80,467m radius)
        binding.zoomSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    // Convert slider position (0-200) to zoom level (0.25-50 miles)
                    val miles = 0.25 + (progress * (50.0 - 0.25) / 200.0)
                    val zoomLevel = ((miles * 1609.344) / 100.0).toFloat() // Convert miles to meters, then to zoom level
                    saveZoom(zoomLevel)
                    
                    // Update zoom label to show actual scale using UnitManager
                    val radiusMeters = zoomLevel * 100.0
                    val distanceText = UnitManager.metersToDistanceShort(radiusMeters, requireContext())
                    binding.zoomLabel.text = getString(R.string.map_scale_radius_format, distanceText)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    /**
     * Load settings from SharedPreferences (following SettingsActivity pattern)
     */
    private fun loadSettings() {
        Log.d("MinimapSettingsFragment", "Loading settings from SharedPreferences")
        
        // Load orientation
        val orientation = prefs.getString("minimap_orientation", MinimapOrientation.NORTH_UP.name)
        when (orientation) {
            MinimapOrientation.NORTH_UP.name -> binding.orientationNorthUp.isChecked = true
            MinimapOrientation.HEADING_UP.name -> binding.orientationHeadingUp.isChecked = true
            MinimapOrientation.AUTO.name -> binding.orientationAuto.isChecked = true
        }
        
        // Load size
        val size = prefs.getString("minimap_size", MinimapSize.MEDIUM.name)
        when (size) {
            MinimapSize.SMALL.name -> binding.sizeSmall.isChecked = true
            MinimapSize.MEDIUM.name -> binding.sizeMedium.isChecked = true
            MinimapSize.LARGE.name -> binding.sizeLarge.isChecked = true
        }
        
        // Load position
        val position = prefs.getString("minimap_position", MinimapPosition.BOTTOM_RIGHT.name)
        when (position) {
            MinimapPosition.TOP_LEFT.name -> binding.positionTopLeft.isChecked = true
            MinimapPosition.TOP_RIGHT.name -> binding.positionTopRight.isChecked = true
            MinimapPosition.BOTTOM_LEFT.name -> binding.positionBottomLeft.isChecked = true
            MinimapPosition.BOTTOM_RIGHT.name -> binding.positionBottomRight.isChecked = true
            MinimapPosition.CENTER.name -> binding.positionCenter.isChecked = true
        }
        
        // Load features
        val features = prefs.getStringSet("minimap_features", setOf(
            MinimapFeature.PEERS.name,
            MinimapFeature.WAYPOINTS.name,
            MinimapFeature.GRID.name,
            MinimapFeature.NORTH_INDICATOR.name
        )) ?: emptySet()
        
        binding.featurePeers.isChecked = features.contains(MinimapFeature.PEERS.name)
        binding.featureWaypoints.isChecked = features.contains(MinimapFeature.WAYPOINTS.name)
        binding.featureGrid.isChecked = features.contains(MinimapFeature.GRID.name)
        binding.featureNorthIndicator.isChecked = features.contains(MinimapFeature.NORTH_INDICATOR.name)
        binding.featureDistanceRings.isChecked = features.contains(MinimapFeature.DISTANCE_RINGS.name)
        binding.featureCompassQuality.isChecked = features.contains(MinimapFeature.COMPASS_QUALITY.name)
        binding.featureBatteryLevel.isChecked = features.contains(MinimapFeature.BATTERY_LEVEL.name)
        binding.featureNetworkStatus.isChecked = features.contains(MinimapFeature.NETWORK_STATUS.name)
        
        // Load zoom
        val zoomLevel = prefs.getFloat("minimap_zoom", 1.0f)
        // Convert zoom level back to slider position (0-200) for 0.25-50 miles range
        val radiusMeters = zoomLevel * 100.0
        val radiusMiles = radiusMeters * 0.000621371
        val sliderPosition = ((radiusMiles - 0.25) * 200.0 / (50.0 - 0.25)).toInt().coerceIn(0, 200)
        binding.zoomSlider.progress = sliderPosition
        
        // Update zoom label to show actual scale using UnitManager
        val distanceText = UnitManager.metersToDistanceShort(radiusMeters, requireContext())
        binding.zoomLabel.text = getString(R.string.map_scale_radius_format, distanceText)
    }
    
    /**
     * Save orientation setting
     */
    private fun saveOrientation(orientation: MinimapOrientation) {
        prefs.edit().putString("minimap_orientation", orientation.name).apply()
        Log.d("MinimapSettingsFragment", "Saved orientation: ${orientation.name}")
        forceRender()
    }
    
    /**
     * Save size setting
     */
    private fun saveSize(size: MinimapSize) {
        prefs.edit().putString("minimap_size", size.name).apply()
        Log.d("MinimapSettingsFragment", "Saved size: ${size.name}")
        forceRender()
    }
    
    /**
     * Save position setting
     */
    private fun savePosition(position: MinimapPosition) {
        prefs.edit().putString("minimap_position", position.name).apply()
        Log.d("MinimapSettingsFragment", "Saved position: ${position.name}")
        forceRender()
    }
    
    /**
     * Save zoom setting
     */
    private fun saveZoom(zoomLevel: Float) {
        prefs.edit().putFloat("minimap_zoom", zoomLevel).apply()
        Log.d("MinimapSettingsFragment", "Saved zoom: $zoomLevel")
        forceRender()
    }
    
    /**
     * Toggle feature setting
     */
    private fun toggleFeature(feature: MinimapFeature, enabled: Boolean) {
        val currentFeatures = prefs.getStringSet("minimap_features", setOf(
            MinimapFeature.PEERS.name,
            MinimapFeature.WAYPOINTS.name,
            MinimapFeature.GRID.name,
            MinimapFeature.NORTH_INDICATOR.name
        ))?.toMutableSet() ?: mutableSetOf()
        
        if (enabled) {
            currentFeatures.add(feature.name)
        } else {
            currentFeatures.remove(feature.name)
        }
        
        prefs.edit().putStringSet("minimap_features", currentFeatures).apply()
        Log.d("MinimapSettingsFragment", "Toggled feature ${feature.name}: $enabled")
        forceRender()
    }
    
    /**
     * Force a render when settings change
     */
    private fun forceRender() {
        // Get the VuzixManager from MinimapService and force a render
        try {
            val minimapService = minimapController.getMinimapService()
            val vuzixManager = minimapService.getVuzixManager()
            vuzixManager.forceRender()
            Log.d("MinimapSettingsFragment", "Force render called")
        } catch (e: Exception) {
            Log.e("MinimapSettingsFragment", "Failed to force render", e)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe minimap enabled state
                minimapController.isMinimapEnabled.collect { isEnabled ->
                    binding.minimapToggle.isChecked = isEnabled
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe Vuzix connection state
                minimapController.isVuzixConnected.collect { isConnected ->
                    binding.connectionStatus.text = if (isConnected) "Connected" else "Disconnected"
                    binding.connectionStatus.setTextColor(
                        if (isConnected) android.graphics.Color.GREEN else android.graphics.Color.RED
                    )
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
