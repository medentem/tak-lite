package com.tak.lite.vuzix

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.tak.lite.databinding.FragmentMinimapSettingsBinding
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
        setupUI()
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
                minimapController.setMinimapOrientation(MinimapOrientation.NORTH_UP)
            }
        }
        binding.orientationHeadingUp.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapOrientation(MinimapOrientation.HEADING_UP)
            }
        }
        binding.orientationAuto.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapOrientation(MinimapOrientation.AUTO)
            }
        }

        // Size selection
        binding.sizeSmall.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapSize(MinimapSize.SMALL)
            }
        }
        binding.sizeMedium.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapSize(MinimapSize.MEDIUM)
            }
        }
        binding.sizeLarge.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapSize(MinimapSize.LARGE)
            }
        }

        // Position selection
        binding.positionTopLeft.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapPosition(MinimapPosition.TOP_LEFT)
            }
        }
        binding.positionTopRight.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapPosition(MinimapPosition.TOP_RIGHT)
            }
        }
        binding.positionBottomLeft.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapPosition(MinimapPosition.BOTTOM_LEFT)
            }
        }
        binding.positionBottomRight.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapPosition(MinimapPosition.BOTTOM_RIGHT)
            }
        }
        binding.positionCenter.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.setMinimapPosition(MinimapPosition.CENTER)
            }
        }

        // Feature toggles
        binding.featurePeers.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.PEERS)
            }
        }
        binding.featureWaypoints.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.WAYPOINTS)
            }
        }
        binding.featureGrid.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.GRID)
            }
        }
        binding.featureNorthIndicator.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.NORTH_INDICATOR)
            }
        }
        binding.featureDistanceRings.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.DISTANCE_RINGS)
            }
        }
        binding.featureCompassQuality.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.COMPASS_QUALITY)
            }
        }
        binding.featureBatteryLevel.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.BATTERY_LEVEL)
            }
        }
        binding.featureNetworkStatus.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                minimapController.toggleMinimapFeature(MinimapFeature.NETWORK_STATUS)
            }
        }

        // Zoom control
        binding.zoomSlider.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val zoomLevel = progress / 100f
                    minimapController.setMinimapZoom(zoomLevel)
                }
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
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

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Observe minimap settings
                minimapController.minimapSettings.collect { settings ->
                    updateUIFromSettings(settings)
                }
            }
        }
    }

    private fun updateUIFromSettings(settings: MinimapSettings) {
        // Update orientation
        when (settings.orientation) {
            MinimapOrientation.NORTH_UP -> binding.orientationNorthUp.isChecked = true
            MinimapOrientation.HEADING_UP -> binding.orientationHeadingUp.isChecked = true
            MinimapOrientation.AUTO -> binding.orientationAuto.isChecked = true
        }

        // Update size
        when (settings.size) {
            MinimapSize.SMALL -> binding.sizeSmall.isChecked = true
            MinimapSize.MEDIUM -> binding.sizeMedium.isChecked = true
            MinimapSize.LARGE -> binding.sizeLarge.isChecked = true
        }

        // Update position
        when (settings.position) {
            MinimapPosition.TOP_LEFT -> binding.positionTopLeft.isChecked = true
            MinimapPosition.TOP_RIGHT -> binding.positionTopRight.isChecked = true
            MinimapPosition.BOTTOM_LEFT -> binding.positionBottomLeft.isChecked = true
            MinimapPosition.BOTTOM_RIGHT -> binding.positionBottomRight.isChecked = true
            MinimapPosition.CENTER -> binding.positionCenter.isChecked = true
        }

        // Update features
        binding.featurePeers.isChecked = settings.features.contains(MinimapFeature.PEERS)
        binding.featureWaypoints.isChecked = settings.features.contains(MinimapFeature.WAYPOINTS)
        binding.featureGrid.isChecked = settings.features.contains(MinimapFeature.GRID)
        binding.featureNorthIndicator.isChecked = settings.features.contains(MinimapFeature.NORTH_INDICATOR)
        binding.featureDistanceRings.isChecked = settings.features.contains(MinimapFeature.DISTANCE_RINGS)
        binding.featureCompassQuality.isChecked = settings.features.contains(MinimapFeature.COMPASS_QUALITY)
        binding.featureBatteryLevel.isChecked = settings.features.contains(MinimapFeature.BATTERY_LEVEL)
        binding.featureNetworkStatus.isChecked = settings.features.contains(MinimapFeature.NETWORK_STATUS)

        // Update zoom
        binding.zoomSlider.progress = (settings.zoomLevel * 100).toInt()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
