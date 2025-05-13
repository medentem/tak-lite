package com.tak.lite.ui.audio

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.tak.lite.R
import com.tak.lite.data.model.AudioChannel
import com.tak.lite.data.model.AudioSettings
import com.tak.lite.databinding.AudioControlsBinding
import com.tak.lite.network.MeshNetworkManagerImpl
import com.tak.lite.util.PermissionManager
import com.tak.lite.viewmodel.AudioViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    private val meshNetworkViewModel: MeshNetworkViewModel by viewModels({ requireActivity() })

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

        // Group menu button opens the channel overlay
        binding.groupMenuButton.setOnClickListener {
            val overlayView: View
            if (binding.talkGroupOverlayContainer.childCount == 0) {
                overlayView = LayoutInflater.from(requireContext()).inflate(R.layout.talk_group_overlay, binding.talkGroupOverlayContainer, false)
                binding.talkGroupOverlayContainer.addView(overlayView)
                // Close button inside overlay
                overlayView.findViewById<View>(R.id.closeTalkGroupOverlayButton).setOnClickListener {
                    binding.talkGroupOverlayContainer.visibility = View.GONE
                }
                // Setup TalkGroupAdapter (for channels)
                val talkGroupList = overlayView.findViewById<RecyclerView>(R.id.talkGroupList)
                val talkGroupAdapter = TalkGroupAdapter(
                    onGroupSelected = { channel ->
                        viewModel.selectChannel(channel.id)
                    },
                    getUserName = { userId ->
                        // Use nickname if available
                        val peers = (meshNetworkViewModel.uiState.value as? com.tak.lite.viewmodel.MeshNetworkUiState.Connected)?.peers ?: emptyList()
                        peers.find { it.id == userId }?.nickname ?: userId
                    },
                    getIsActive = { channel ->
                        viewModel.settings.value.selectedChannelId == channel.id
                    }
                )
                talkGroupList.layoutManager = LinearLayoutManager(requireContext())
                talkGroupList.adapter = talkGroupAdapter
                // Observe channels and update adapter
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.channels.collectLatest { channels ->
                        talkGroupAdapter.submitList(channels)
                    }
                }
                // Wire up indicator/animation logic
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.settings.collectLatest { settings ->
                        talkGroupAdapter.setActiveGroup(settings.selectedChannelId)
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.isTransmitting.collectLatest { isTransmitting ->
                        talkGroupAdapter.setTransmittingGroup(if (isTransmitting) viewModel.settings.value.selectedChannelId else null)
                    }
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    viewModel.isReceiving.collectLatest { isReceiving ->
                        talkGroupAdapter.setReceivingGroup(if (isReceiving) viewModel.settings.value.selectedChannelId else null)
                    }
                }
                // Add Channel button
                overlayView.findViewById<View>(R.id.addTalkGroupButton).setOnClickListener {
                    showAddChannelDialog()
                }
                // Manage Channels button
                overlayView.findViewById<View>(R.id.manageChannelsButton).setOnClickListener {
                    val intent = android.content.Intent(requireContext(), ChannelManagementActivity::class.java)
                    requireContext().startActivity(intent)
                    binding.talkGroupOverlayContainer.visibility = View.GONE
                }
            } else {
                overlayView = binding.talkGroupOverlayContainer.getChildAt(0)
            }
            overlayView.findViewById<View>(R.id.addTalkGroupButton).setOnClickListener {
                showAddChannelDialog()
            }
            overlayView.findViewById<View>(R.id.manageChannelsButton).setOnClickListener {
                val intent = android.content.Intent(requireContext(), ChannelManagementActivity::class.java)
                requireContext().startActivity(intent)
                binding.talkGroupOverlayContainer.visibility = View.GONE
            }
            binding.talkGroupOverlayContainer.visibility = View.VISIBLE
        }

        // Clicking outside overlay content closes the overlay
        binding.talkGroupOverlayContainer.setOnClickListener {
            binding.talkGroupOverlayContainer.visibility = View.GONE
        }
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
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    if (!permissionManager.hasRequiredPermissions()) {
                        showErrorSnackbar("Audio permissions are required to transmit.")
                        return@setOnTouchListener true
                    }
                    if (viewModel.connectionState.value != MeshNetworkManagerImpl.ConnectionState.CONNECTED) {
                        showErrorSnackbar("Mesh network is not connected.")
                        return@setOnTouchListener true
                    }
                    isPttEnabled = true
                    viewModel.setPTTState(true)
                    updatePttButton()
                    startAudioStreamingService()
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    isPttEnabled = false
                    viewModel.setPTTState(false)
                    updatePttButton()
                    stopAudioStreamingService()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAudioStreamingService() {
        val context = requireContext().applicationContext
        val settings = viewModel.settings.value.copy(isPTTHeld = true)
        val intent = android.content.Intent(context, com.tak.lite.service.AudioStreamingService::class.java)
        intent.putExtra("isMuted", settings.isMuted)
        intent.putExtra("volume", settings.volume)
        intent.putExtra("selectedChannelId", settings.selectedChannelId)
        intent.putExtra("isPTTHeld", true)
        context.startService(intent)
    }

    private fun stopAudioStreamingService() {
        val context = requireContext().applicationContext
        val intent = android.content.Intent(context, com.tak.lite.service.AudioStreamingService::class.java)
        context.stopService(intent)
    }

    private fun updatePttButton() {
        binding.pttButton.isSelected = isPttEnabled
        if (isPttEnabled) {
            binding.pttButton.setBackgroundTintList(android.content.res.ColorStateList.valueOf(android.graphics.Color.RED))
            binding.connectionStatus.text = "Transmitting..."
        } else {
            binding.pttButton.setBackgroundTintList(null)
            // Restore connection status based on actual state
            updateConnectionState(viewModel.connectionState.value)
        }
    }

    private fun showErrorSnackbar(message: String) {
        Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
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
                adapter.setActiveChannel(settings.selectedChannelId)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.connectionState.collectLatest { state ->
                updateConnectionState(state)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isTransmitting.collectLatest { isTransmitting ->
                adapter.setTransmittingChannel(if (isTransmitting) viewModel.settings.value.selectedChannelId else null)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isReceiving.collectLatest { isReceiving ->
                adapter.setReceivingChannel(if (isReceiving) viewModel.settings.value.selectedChannelId else null)
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