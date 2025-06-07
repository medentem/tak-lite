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

class ChannelController(
    private val activity: Activity,
    private val channelViewModel: ChannelViewModel,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private var channelCollectionJob: Job? = null

    fun setupChannelButton(peerIdToNickname: Map<String, String?>) {
        val channelButton = activity.findViewById<ImageButton>(R.id.groupAudioButton)
        channelButton.setOnClickListener {
            val rootView = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return@setOnClickListener
            val overlayTag = "ChannelOverlay"
            if (rootView.findViewWithTag<View>(overlayTag) == null) {
                Log.d("ChannelController", "Creating channel overlay")
                val overlay = activity.layoutInflater.inflate(R.layout.channel_overlay, rootView, false)
                overlay.tag = overlayTag
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
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                    }.start()
                }
                val channelList = overlay.findViewById<RecyclerView>(R.id.channelList)
                val channelAdapter = ChannelAdapter(
                    onGroupSelected = { channel ->
                        Log.d("ChannelController", "Channel selected: ${channel.name}")
                        channelViewModel.selectChannel(channel.id)
                        channelCollectionJob?.cancel()
                        channelCollectionJob = null
                        overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                            rootView.removeView(overlay)
                        }.start()
                    },
                    getUserName = { userId -> peerIdToNickname[userId] ?: userId },
                    getIsActive = { channel -> 
                        val isActive = channelViewModel.settings.value.selectedChannelId == channel.id
                        Log.d("ChannelController", "Checking if channel ${channel.name} is active: $isActive")
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
                        channelAdapter.submitList(channels)
                    }
                }
                
                overlay.findViewById<View>(R.id.addChannelButton)?.setOnClickListener {
                    showAddChannelDialog()
                }
                overlay.findViewById<View>(R.id.manageChannelsButton)?.setOnClickListener {
                    channelCollectionJob?.cancel()
                    channelCollectionJob = null
                    val intent = Intent(activity, ChannelManagementActivity::class.java)
                    activity.startActivity(intent)
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                    }.start()
                }
                overlay.setOnClickListener { /* consume clicks */ }
            }
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