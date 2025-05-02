package com.tak.lite.ui.audio

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
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
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.databinding.AudioControlsBinding
import com.tak.lite.network.MeshNetworkManagerImpl
import com.tak.lite.util.PermissionManager
import com.tak.lite.viewmodel.AudioViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.app.AlertDialog
import android.content.DialogInterface
import androidx.lifecycle.ViewModelProvider

@AndroidEntryPoint
class AudioFragment : Fragment() {

    private var _binding: AudioControlsBinding? = null
    private val binding get() = _binding!!
    private lateinit var viewModel: AudioViewModel
    private lateinit var layoutManager: LinearLayoutManager
    private lateinit var adapter: ChannelAdapter
    private var isPttEnabled = false

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
        viewModel = ViewModelProvider(this)[AudioViewModel::class.java]
        
        checkPermissions()
        setupVolumeControls()
        setupChannelList()
        setupPttButton()
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

    private fun setupVolumeControls() {
        binding.volumeSlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setVolume(value.toInt())
            }
        }
        
        binding.muteButton.setOnClickListener {
            viewModel.toggleMute()
        }
    }

    private fun setupChannelList() {
        layoutManager = LinearLayoutManager(context)
        adapter = ChannelAdapter(
            onChannelSelected = { channel ->
                viewModel.selectChannel(channel.id)
            },
            onChannelDeleted = { channel ->
                showDeleteChannelDialog(channel)
            }
        )
        
        binding.channelList.apply {
            this.layoutManager = layoutManager
            this.adapter = this@AudioFragment.adapter
        }

        binding.addChannelButton.setOnClickListener {
            showAddChannelDialog()
        }
    }

    private fun setupPttButton() {
        binding.pttButton.setOnClickListener {
            isPttEnabled = !isPttEnabled
            viewModel.setPTTState(isPttEnabled)
            updatePttButton()
        }
    }

    private fun updatePttButton() {
        binding.pttButton.isSelected = isPttEnabled
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                adapter.submitList(channels)
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
        binding.volumeSlider.value = settings.volume.toFloat()
        binding.muteButton.text = if (settings.isMuted) "Unmute" else "Mute"
        binding.connectionStatus.text = if (settings.isPTTHeld) "PTT Active" else "PTT Inactive"
    }

    private fun updateConnectionState(state: MeshNetworkManagerImpl.ConnectionState) {
        binding.connectionStatus.text = when (state) {
            MeshNetworkManagerImpl.ConnectionState.CONNECTED -> "Connected"
            MeshNetworkManagerImpl.ConnectionState.CONNECTING -> "Connecting..."
            MeshNetworkManagerImpl.ConnectionState.DISCONNECTED -> "Disconnected"
            else -> "Unknown"
        }
    }

    private fun showDeleteChannelDialog(channel: AudioChannel) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Channel")
            .setMessage("Are you sure you want to delete channel '${channel.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteChannel(channel.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddChannelDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_channel, null)
        val channelNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.channelNameInput)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Add Channel")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = channelNameInput.text?.toString() ?: return@setPositiveButton
                viewModel.createChannel(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
} 