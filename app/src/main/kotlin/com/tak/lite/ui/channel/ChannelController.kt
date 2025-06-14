package com.tak.lite.ui.channel

import android.app.Activity
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.data.model.IChannel
import com.tak.lite.di.MeshConnectionState
import com.tak.lite.network.MeshPeer
import com.tak.lite.network.MeshProtocolProvider
import com.tak.lite.ui.peer.PeerAdapter
import com.tak.lite.viewmodel.ChannelViewModel
import com.tak.lite.viewmodel.MessageViewModel
import com.tak.lite.viewmodel.MeshNetworkViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

class ChannelController @Inject constructor(
    private val activity: Activity,
    private val channelViewModel: ChannelViewModel,
    private val messageViewModel: MessageViewModel,
    private val meshNetworkViewModel: MeshNetworkViewModel,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val meshProtocolProvider: MeshProtocolProvider
) {
    private var channelCollectionJob: Job? = null
    private var protocolCollectionJob: Job? = null
    private var connectionStateJob: Job? = null
    private var settingsCollectionJob: Job? = null
    private var peerCollectionJob: Job? = null
    private var currentOverlay: View? = null
    private var channelAdapter: ChannelAdapter? = null
    private var peerAdapter: PeerAdapter? = null
    private var showOlderPeers = false

    fun setupChannelButton(peerIdToNickname: Map<String, String?>) {
        val channelButton = activity.findViewById<ImageButton>(R.id.groupAudioButton)
        channelButton.setOnClickListener {
            val rootView = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return@setOnClickListener
            val overlayTag = "ChannelOverlay"
            if (rootView.findViewWithTag<View>(overlayTag) == null) {
                Log.d("ChannelController", "Creating channel overlay")
                // Add scrim
                val scrimTag = "ChannelOverlayScrim"
                val scrim = View(activity).apply {
                    tag = scrimTag
                    setBackgroundColor(0x66000000) // semi-transparent black
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                    setOnClickListener {
                        // Dismiss overlay and scrim
                        val overlay = rootView.findViewWithTag<View>(overlayTag)
                        val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.channel_overlay_width)
                        overlay?.animate()?.translationX(overlayWidth.toFloat())?.setDuration(300)?.withEndAction {
                            rootView.removeView(overlay)
                            rootView.removeView(this)
                            channelCollectionJob?.cancel()
                            channelCollectionJob = null
                            protocolCollectionJob?.cancel()
                            protocolCollectionJob = null
                            connectionStateJob?.cancel()
                            connectionStateJob = null
                            settingsCollectionJob?.cancel()
                            settingsCollectionJob = null
                            peerCollectionJob?.cancel()
                            peerCollectionJob = null
                            currentOverlay = null
                        }?.start()
                    }
                }
                rootView.addView(scrim)
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

                // Setup peer list button
                overlay.findViewById<ImageButton>(R.id.peerListButton)?.setOnClickListener {
                    showPeerList(rootView)
                }

                overlay.findViewById<View>(R.id.closeChannelOverlayButton)?.setOnClickListener {
                    channelCollectionJob?.cancel()
                    channelCollectionJob = null
                    protocolCollectionJob?.cancel()
                    protocolCollectionJob = null
                    connectionStateJob?.cancel()
                    connectionStateJob = null
                    settingsCollectionJob?.cancel()
                    settingsCollectionJob = null
                    peerCollectionJob?.cancel()
                    peerCollectionJob = null
                    currentOverlay = null
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                        // Remove scrim as well
                        val scrimView = rootView.findViewWithTag<View>(scrimTag)
                        if (scrimView != null) rootView.removeView(scrimView)
                    }.start()
                }
                val channelList = overlay.findViewById<RecyclerView>(R.id.channelList)
                channelAdapter = ChannelAdapter(
                    onGroupSelected = { channel ->
                        Log.d("ChannelController", "Channel selected: ${channel.name} (${channel.id})")
                        channelViewModel.selectChannel(channel.id)
                        Log.d("ChannelController", "After selectChannel, settings value: ${channelViewModel.settings.value.selectedChannelId}")
                    },
                    onDelete = { channel ->
                        Log.d("ChannelController", "Deleting channel: ${channel.name} (${channel.id})")
                        channelViewModel.deleteChannel(channel.id)
                    },
                    getIsActive = { channel -> 
                        val isActive = channelViewModel.settings.value.selectedChannelId == channel.id
                        Log.d("ChannelController", "Checking if channel ${channel.name} (${channel.id}) is active. Current selectedChannelId: ${channelViewModel.settings.value.selectedChannelId}, isActive: $isActive")
                        isActive
                    }
                )
                channelList.layoutManager = LinearLayoutManager(activity)
                channelList.adapter = channelAdapter
                
                // Add click listener to root view for outside tap dismissal
                rootView.setOnClickListener {
                    val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.channel_overlay_width)
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                        channelCollectionJob?.cancel()
                        channelCollectionJob = null
                        protocolCollectionJob?.cancel()
                        protocolCollectionJob = null
                        connectionStateJob?.cancel()
                        connectionStateJob = null
                        settingsCollectionJob?.cancel()
                        settingsCollectionJob = null
                        peerCollectionJob?.cancel()
                        peerCollectionJob = null
                        currentOverlay = null
                    }.start()
                }
                
                // Prevent clicks on the overlay from propagating to the scrim
                overlay.setOnClickListener { /* consume clicks */ }

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
            }
        }
    }

    private fun showPeerList(rootView: FrameLayout) {
        val overlayTag = "PeerListOverlay"
        if (rootView.findViewWithTag<View>(overlayTag) != null) return

        // Add scrim
        val scrimTag = "PeerListOverlayScrim"
        val scrim = View(activity).apply {
            tag = scrimTag
            setBackgroundColor(0x00000000) // fully transparent
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            setOnClickListener {
                // Dismiss overlay and scrim
                val overlay = rootView.findViewWithTag<View>(overlayTag)
                val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.channel_overlay_width)
                overlay?.animate()?.translationX(overlayWidth.toFloat())?.setDuration(300)?.withEndAction {
                    rootView.removeView(overlay)
                    rootView.removeView(this)
                    peerCollectionJob?.cancel()
                    peerCollectionJob = null
                }?.start()
            }
        }
        rootView.addView(scrim)

        val overlay = activity.layoutInflater.inflate(R.layout.peer_list_overlay, rootView, false)
        overlay.tag = overlayTag
        val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.channel_overlay_width)
        val params = FrameLayout.LayoutParams(overlayWidth, FrameLayout.LayoutParams.MATCH_PARENT)
        params.gravity = android.view.Gravity.END
        overlay.layoutParams = params
        overlay.translationX = overlayWidth.toFloat()
        rootView.addView(overlay)
        overlay.animate().translationX(0f).setDuration(300).start()

        // Setup close button
        overlay.findViewById<View>(R.id.closePeerListButton)?.setOnClickListener {
            overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                rootView.removeView(overlay)
                // Remove scrim as well
                val scrimView = rootView.findViewWithTag<View>(scrimTag)
                if (scrimView != null) rootView.removeView(scrimView)
                peerCollectionJob?.cancel()
                peerCollectionJob = null
            }.start()
        }

        // Prevent clicks on the overlay from propagating to the scrim
        overlay.setOnClickListener { /* consume clicks */ }

        // Setup peer list
        val peerList = overlay.findViewById<RecyclerView>(R.id.peerList)
        peerAdapter = PeerAdapter(
            onChatClick = { _: MeshPeer ->
                // We could close the peer list overlay, but no
            },
            messageViewModel = messageViewModel,
            meshNetworkViewModel = meshNetworkViewModel,
            lifecycleScope = lifecycleScope
        )
        peerList.layoutManager = LinearLayoutManager(activity)
        peerList.adapter = peerAdapter

        // Setup divider click listener
        peerAdapter?.setOnDividerClickListener {
            showOlderPeers = !showOlderPeers
            peerAdapter?.toggleOlderPeers()
        }

        // Observe peers
        peerCollectionJob?.cancel()
        peerCollectionJob = lifecycleScope.launch {
            meshProtocolProvider.protocol.value.peers.collectLatest { peers ->
                // Filter out our own node and sort peers by last seen time
                val selfId = meshProtocolProvider.protocol.value.localNodeIdOrNickname.value
                val filteredPeers = peers.filter { it.id != selfId }
                val sortedPeers = filteredPeers.sortedByDescending { it.lastSeen }
                peerAdapter?.submitList(sortedPeers)
                
                // Update peer count in header
                overlay.findViewById<TextView>(R.id.peerListTitle)?.text = "Peers (${filteredPeers.size})"
            }
        }

        // Add periodic refresh for last seen times
        lifecycleScope.launch {
            while (true) {
                delay(10000) // Update every 10 seconds
                peerAdapter?.notifyDataSetChanged() // This will trigger a rebind of all items
            }
        }

        // Update connection state
        updateConnectionState(overlay, meshProtocolProvider.protocol.value.connectionState.value)
    }

    private fun updateConnectionState(overlay: View, state: MeshConnectionState) {
        val disabledOverlay = overlay.findViewById<View>(R.id.disabledOverlay)
        disabledOverlay?.visibility = when (state) {
            is MeshConnectionState.Connected -> View.GONE
            is MeshConnectionState.Connecting -> View.VISIBLE
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
                peerCollectionJob?.cancel()
                peerCollectionJob = null
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