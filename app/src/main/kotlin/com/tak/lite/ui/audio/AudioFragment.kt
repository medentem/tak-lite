package com.tak.lite.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tak.lite.R
import com.tak.lite.data.model.AudioChannel
import com.tak.lite.databinding.AudioControlsBinding
import com.tak.lite.network.MeshNetworkManagerImpl
import com.tak.lite.util.PermissionManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class AudioFragment : Fragment() {

    private var _binding: AudioControlsBinding? = null
    private val binding get() = _binding!!

    private val viewModel: AudioViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter

    @Inject
    lateinit var permissionManager: PermissionManager

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            viewModel.connect()
        } else {
            showPermissionDeniedMessage()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = AudioControlsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        checkPermissions()
        setupChannelList()
        setupControls()
        observeViewModel()
    }

    private fun checkPermissions() {
        if (permissionManager.hasRequiredPermissions()) {
            viewModel.connect()
        } else {
            requestPermissions()
        }
    }

    private fun requestPermissions() {
        requestPermissionLauncher.launch(PermissionManager.REQUIRED_PERMISSIONS)
    }

    private fun showPermissionDeniedMessage() {
        Snackbar.make(
            binding.root,
            "Audio permissions are required for PTT functionality",
            Snackbar.LENGTH_INDEFINITE
        ).setAction("Grant") {
            requestPermissions()
        }.show()
    }

    private fun setupChannelList() {
        channelAdapter = ChannelAdapter(
            onChannelSelected = { channel ->
                viewModel.selectChannel(channel.id)
            },
            onChannelDeleted = { channel ->
                showDeleteChannelDialog(channel)
            }
        )

        binding.channelList.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = channelAdapter
        }
    }

    private fun setupControls() {
        binding.apply {
            // PTT Button
            pttButton.setOnTouchListener { _, event ->
                when (event.action) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        if (permissionManager.hasRequiredPermissions()) {
                            viewModel.setPTTState(true)
                        } else {
                            requestPermissions()
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> {
                        viewModel.setPTTState(false)
                        true
                    }
                    else -> false
                }
            }

            // Volume Slider
            volumeSlider.addOnChangeListener { _, value, _ ->
                viewModel.setVolume(value.toInt())
            }

            // Mute Button
            muteButton.setOnClickListener {
                viewModel.toggleMute()
            }

            // Add Channel Button
            addChannelButton.setOnClickListener {
                showAddChannelDialog()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                channelAdapter.submitList(channels)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.settings.collectLatest { settings ->
                updateUI(settings)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                updateConnectionState(state)
            }
        }
    }

    private fun updateUI(settings: AudioSettings) {
        binding.apply {
            volumeSlider.value = settings.volume.toFloat()
            muteButton.text = if (settings.isMuted) "Unmute" else "Mute"
            pttButton.isActivated = settings.isPTTHeld
        }
    }

    private fun updateConnectionState(state: MeshNetworkManagerImpl.ConnectionState) {
        when (state) {
            MeshNetworkManagerImpl.ConnectionState.CONNECTED -> {
                binding.connectionStatus.text = "Connected"
                binding.connectionStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_green_dark)
                )
            }
            MeshNetworkManagerImpl.ConnectionState.CONNECTING -> {
                binding.connectionStatus.text = "Connecting..."
                binding.connectionStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_orange_dark)
                )
            }
            MeshNetworkManagerImpl.ConnectionState.DISCONNECTED -> {
                binding.connectionStatus.text = "Disconnected"
                binding.connectionStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                )
            }
            MeshNetworkManagerImpl.ConnectionState.ERROR -> {
                binding.connectionStatus.text = "Error"
                binding.connectionStatus.setTextColor(
                    ContextCompat.getColor(requireContext(), android.R.color.holo_red_dark)
                )
                showConnectionError()
            }
        }
    }

    private fun showConnectionError() {
        Snackbar.make(
            binding.root,
            "Connection error occurred. Attempting to reconnect...",
            Snackbar.LENGTH_LONG
        ).show()
    }

    private fun showAddChannelDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Channel")
            .setView(R.layout.dialog_add_channel)
            .setPositiveButton("Add") { dialog, _ ->
                val input = (dialog as MaterialAlertDialogBuilder)
                    .findViewById<android.widget.EditText>(R.id.channelNameInput)
                input?.text?.toString()?.let { name ->
                    viewModel.createChannel(name)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteChannelDialog(channel: AudioChannel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Channel")
            .setMessage("Are you sure you want to delete ${channel.name}?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteChannel(channel.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 