package com.tak.lite.ui.channel

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.viewmodel.ChannelViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.di.MeshConnectionState
import javax.inject.Inject
import android.view.LayoutInflater
import android.view.ViewGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

class ChannelController @Inject constructor(
    private val activity: Activity,
    private val channelViewModel: ChannelViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val meshProtocolProvider: MeshProtocolProvider
) {
    private var channelCollectionJob: Job? = null
    private var protocolCollectionJob: Job? = null
    private var connectionStateJob: Job? = null
    private var settingsCollectionJob: Job? = null
    private var currentOverlay: View? = null
    private var channelAdapter: ChannelAdapter? = null

    fun setupChannelButton(peerIdToNickname: Map<String, String?>) {
        val channelButton = activity.findViewById<ImageButton>(R.id.groupAudioButton)
        channelButton.setOnClickListener {
            val rootView = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return@setOnClickListener
            val overlayTag = "ChannelOverlay"
            if (rootView.findViewWithTag<View>(overlayTag) == null) {
                Log.d("ChannelController", "Creating channel overlay")
                val overlay = activity.layoutInflater.inflate(R.layout.channel_overlay, rootView, false)
                overlay.tag = overlayTag
                currentOverlay = overlay
                val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.channel_overlay_width)
                val params = FrameLayout.LayoutParams(overlayWidth, FrameLayout.LayoutParams.MATCH_PARENT)
                params.gravity = android.view.Gravity.END
                overlay.layoutParams = params
                overlay.translationX = overlayWidth.toFloat()
                rootView.addView(overlay)
                overlay.animate().translationX(0f).setDuration(300).start()
                overlay.findViewById<View>(R.id.closeChannelOverlayButton)?.setOnClickListener {
                    channelCollectionJob?.cancel()
                    channelCollectionJob = null
                    protocolCollectionJob?.cancel()
                    protocolCollectionJob = null
                    connectionStateJob?.cancel()
                    connectionStateJob = null
                    settingsCollectionJob?.cancel()
                    settingsCollectionJob = null
                    currentOverlay = null
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                    }.start()
                }
                val channelList = overlay.findViewById<RecyclerView>(R.id.channelList)
                channelAdapter = ChannelAdapter(
                    onGroupSelected = { channel ->
                        Log.d("ChannelController", "Channel selected: ${channel.name} (${channel.id})")
                        channelViewModel.selectChannel(channel.id)
                        Log.d("ChannelController", "After selectChannel, settings value: ${channelViewModel.settings.value.selectedChannelId}")
                        channelCollectionJob?.cancel()
                        channelCollectionJob = null
                        protocolCollectionJob?.cancel()
                        protocolCollectionJob = null
                        connectionStateJob?.cancel()
                        connectionStateJob = null
                        settingsCollectionJob?.cancel()
                        settingsCollectionJob = null
                        currentOverlay = null
                        overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                            rootView.removeView(overlay)
                        }.start()
                    },
                    getUserName = { userId -> peerIdToNickname[userId] ?: userId },
                    getIsActive = { channel -> 
                        val isActive = channelViewModel.settings.value.selectedChannelId == channel.id
                        Log.d("ChannelController", "Checking if channel ${channel.name} (${channel.id}) is active. Current selectedChannelId: ${channelViewModel.settings.value.selectedChannelId}, isActive: $isActive")
                        isActive
                    }
                )
                channelList.layoutManager = LinearLayoutManager(activity)
                channelList.adapter = channelAdapter
                
                // Cancel any existing collection
                channelCollectionJob?.cancel()
                channelCollectionJob = lifecycleScope.launch {
                    Log.d("ChannelController", "Starting to observe channels")
                    channelViewModel.channels.collectLatest { channels ->
                        Log.d("ChannelController", "Received channel update in UI: ${channels.map { "${it.name} (${it.id})" }}")
                        channelAdapter?.submitList(channels)
                    }
                }

                // Observe settings changes
                settingsCollectionJob?.cancel()
                settingsCollectionJob = lifecycleScope.launch {
                    Log.d("ChannelController", "Starting to observe settings")
                    channelViewModel.settings.collectLatest { settings ->
                        Log.d("ChannelController", "Settings changed - selectedChannelId: ${settings.selectedChannelId}")
                        channelAdapter?.notifyDataSetChanged()
                    }
                }

                // Observe protocol changes
                protocolCollectionJob?.cancel()
                protocolCollectionJob = lifecycleScope.launch {
                    meshProtocolProvider.protocol.collectLatest { protocol ->
                        Log.d("ChannelController", "Protocol changed, allowsChannelManagement: ${protocol.allowsChannelManagement}")
                        updateChannelManagementButtons(overlay, protocol.allowsChannelManagement)
                        // Only reset channel selection if the protocol type has changed
                        if (protocol.javaClass != meshProtocolProvider.protocol.value.javaClass) {
                            channelViewModel.resetChannelSelection()
                        }
                    }
                }

                // Observe connection state
                connectionStateJob?.cancel()
                connectionStateJob = lifecycleScope.launch {
                    meshProtocolProvider.protocol.value.connectionState.collectLatest { state ->
                        updateConnectionState(overlay, state)
                    }
                }
                
                // Initial setup of channel management buttons
                updateChannelManagementButtons(overlay, meshProtocolProvider.protocol.value.allowsChannelManagement)
                
                // Initial connection state
                updateConnectionState(overlay, meshProtocolProvider.protocol.value.connectionState.value)
                
                overlay.setOnClickListener { /* consume clicks */ }
            }
        }
    }

    private fun updateConnectionState(overlay: View, state: MeshConnectionState) {
        val disabledOverlay = overlay.findViewById<View>(R.id.disabledOverlay)
        disabledOverlay?.visibility = when (state) {
            is MeshConnectionState.Connected -> View.GONE
            is MeshConnectionState.Disconnected -> View.VISIBLE
            is MeshConnectionState.Error -> View.VISIBLE
        }
    }

    private fun updateChannelManagementButtons(overlay: View, allowsChannelManagement: Boolean) {
        if (allowsChannelManagement) {
            overlay.findViewById<View>(R.id.channelManagementButtons)?.visibility = View.VISIBLE
            overlay.findViewById<View>(R.id.addChannelButton)?.setOnClickListener {
                showAddChannelDialog()
            }
            overlay.findViewById<View>(R.id.manageChannelsButton)?.setOnClickListener {
                channelCollectionJob?.cancel()
                channelCollectionJob = null
                protocolCollectionJob?.cancel()
                protocolCollectionJob = null
                connectionStateJob?.cancel()
                connectionStateJob = null
                settingsCollectionJob?.cancel()
                settingsCollectionJob = null
                currentOverlay = null
                val intent = Intent(activity, ChannelManagementActivity::class.java)
                activity.startActivity(intent)
                overlay.animate().translationX(overlay.findViewById<View>(R.id.channelManagementButtons)?.width?.toFloat() ?: 0f).setDuration(300).withEndAction {
                    (overlay.parent as? FrameLayout)?.removeView(overlay)
                }.start()
            }
        } else {
            overlay.findViewById<View>(R.id.channelManagementButtons)?.visibility = View.GONE
        }
    }

    private fun showAddChannelDialog() {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_add_channel, null)
        val channelNameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.channelNameInput)
        com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle("Add Channel")
            .setView(dialogView)
            .setPositiveButton("Add") { _, _ ->
                val name = channelNameInput.text?.toString() ?: return@setPositiveButton
                channelViewModel.createChannel(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
} 