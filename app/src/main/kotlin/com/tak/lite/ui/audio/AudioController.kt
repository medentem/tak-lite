package com.tak.lite.ui.audio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.databinding.ActivityMainBinding
import com.tak.lite.service.AudioStreamingService
import com.tak.lite.viewmodel.AudioViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AudioController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val audioViewModel: AudioViewModel,
    private val lifecycleScope: LifecycleCoroutineScope
) {
    private lateinit var waveformOverlayContainer: View
    private lateinit var waveformView: WaveformView
    private var waveformJob: kotlinx.coroutines.Job? = null
    private var amplitudes: MutableList<Int> = mutableListOf()
    private var amplitudeReceiver: android.content.BroadcastReceiver? = null

    fun setupAudioUI() {
        waveformOverlayContainer = activity.findViewById(R.id.waveformOverlayContainer)
        waveformView = activity.findViewById(R.id.waveformView)
        waveformOverlayContainer.visibility = View.GONE
        amplitudes = mutableListOf()
        // Register amplitude receiver
        amplitudeReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val amp = intent?.getIntExtra("amplitude", 0) ?: 0
                Log.d("Waveform", "Received amplitude: $amp")
                amplitudes.add(amp)
                if (amplitudes.size > 64) amplitudes.removeAt(0)
                waveformView.setAmplitudes(amplitudes)
            }
        }
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(activity).registerReceiver(amplitudeReceiver!!, android.content.IntentFilter("AUDIO_AMPLITUDE"))
        Log.d("Waveform", "Amplitude receiver registered")
    }

    fun cleanupAudioUI() {
        amplitudeReceiver?.let { androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(activity).unregisterReceiver(it) }
    }

    fun setupPTTButton() {
        binding.pttButton.setOnTouchListener { _, event ->
            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startAudioTransmission()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    stopAudioTransmission()
                    true
                }
                else -> false
            }
        }
    }

    private fun startAudioTransmission() {
        waveformOverlayContainer.visibility = View.VISIBLE
        val intent = Intent(activity, AudioStreamingService::class.java)
        intent.putExtra("isMuted", false)
        intent.putExtra("volume", 50)
        intent.putExtra("selectedChannelId", null as String?)
        intent.putExtra("isPTTHeld", true)
        activity.startService(intent)
    }

    private fun stopAudioTransmission() {
        waveformOverlayContainer.visibility = View.GONE
        waveformJob?.cancel()
        waveformJob = null
        amplitudes.clear()
        waveformView.setAmplitudes(emptyList())
        val intent = Intent(activity, AudioStreamingService::class.java)
        activity.stopService(intent)
    }

    fun setupGroupAudioButton(peerIdToNickname: Map<String, String?>) {
        val groupAudioButton = activity.findViewById<ImageButton>(R.id.groupAudioButton)
        groupAudioButton.setOnClickListener {
            val rootView = activity.findViewById<View>(android.R.id.content) as? FrameLayout ?: return@setOnClickListener
            val overlayTag = "TalkGroupOverlay"
            if (rootView.findViewWithTag<View>(overlayTag) == null) {
                val overlay = activity.layoutInflater.inflate(R.layout.talk_group_overlay, rootView, false)
                overlay.tag = overlayTag
                val overlayWidth = activity.resources.getDimensionPixelSize(R.dimen.talk_group_overlay_width)
                val params = FrameLayout.LayoutParams(overlayWidth, FrameLayout.LayoutParams.MATCH_PARENT)
                params.gravity = android.view.Gravity.END
                overlay.layoutParams = params
                overlay.translationX = overlayWidth.toFloat()
                rootView.addView(overlay)
                overlay.animate().translationX(0f).setDuration(300).start()
                overlay.findViewById<View>(R.id.closeTalkGroupOverlayButton)?.setOnClickListener {
                    overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                        rootView.removeView(overlay)
                    }.start()
                }
                val talkGroupList = overlay.findViewById<RecyclerView>(R.id.talkGroupList)
                val talkGroupAdapter = TalkGroupAdapter(
                    onGroupSelected = { channel ->
                        audioViewModel.selectChannel(channel.id)
                        overlay.animate().translationX(overlayWidth.toFloat()).setDuration(300).withEndAction {
                            rootView.removeView(overlay)
                        }.start()
                    },
                    getUserName = { userId -> peerIdToNickname[userId] ?: userId },
                    getIsActive = { channel -> audioViewModel.settings.value.selectedChannelId == channel.id }
                )
                talkGroupList.layoutManager = LinearLayoutManager(activity)
                talkGroupList.adapter = talkGroupAdapter
                lifecycleScope.launch {
                    audioViewModel.channels.collectLatest { channels ->
                        talkGroupAdapter.submitList(channels)
                    }
                }
                overlay.findViewById<View>(R.id.addTalkGroupButton)?.setOnClickListener {
                    showAddChannelDialog()
                }
                overlay.findViewById<View>(R.id.manageChannelsButton)?.setOnClickListener {
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
                audioViewModel.createChannel(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
} 