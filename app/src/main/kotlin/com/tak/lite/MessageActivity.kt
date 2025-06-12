package com.tak.lite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tak.lite.ui.message.MessageAdapter
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MessageActivity : BaseActivity() {
    private val viewModel: MessageViewModel by viewModels()
    private lateinit var adapter: MessageAdapter
    private lateinit var messageList: RecyclerView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var encryptionIndicator: ImageView
    private var channelId: String = ""

    companion object {
        private const val EXTRA_CHANNEL_ID = "channel_id"

        fun createIntent(context: Context, channelId: String): Intent {
            return Intent(context, MessageActivity::class.java).apply {
                putExtra(EXTRA_CHANNEL_ID, channelId)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message)

        // Get channel ID from intent
        channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: run {
            finish()
            return
        }
        Log.d("MessageActivity", "Starting MessageActivity with channelId: $channelId")

        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Initialize views
        messageList = findViewById(R.id.messageList)
        messageInput = findViewById(R.id.messageInput)
        sendButton = findViewById(R.id.sendButton)
        encryptionIndicator = findViewById(R.id.encryptionIndicator)

        // Setup RecyclerView
        // Get current user's short name from the protocol
        val currentUserShortName = viewModel.getCurrentUserShortName()
        adapter = MessageAdapter(currentUserShortName)
        messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        messageList.adapter = adapter

        // Setup send button
        sendButton.setOnClickListener {
            val message = messageInput.text?.toString()?.trim()
            if (!message.isNullOrEmpty()) {
                viewModel.sendMessage(channelId, message)
                messageInput.text?.clear()
            }
        }

        // Observe messages
        lifecycleScope.launch {
            viewModel.getMessages(channelId).collectLatest { messages ->
                adapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    messageList.smoothScrollToPosition(messages.size - 1)
                }
            }
        }

        // Get channel info and update UI
        lifecycleScope.launch {
            Log.d("MessageActivity", "Starting to observe channel info for channelId: $channelId")
            viewModel.getChannelInfo(channelId).collectLatest { channelInfo ->
                Log.d("MessageActivity", "Received channel info update - name: ${channelInfo.name}, isPkiEncrypted: ${channelInfo.isPkiEncrypted}")
                supportActionBar?.title = channelInfo.name
                
                // Show/hide encryption indicator based on whether this is a direct message
                if (channelId.startsWith("dm_")) {
                    encryptionIndicator.visibility = View.VISIBLE
                    encryptionIndicator.setImageResource(
                        if (channelInfo.isPkiEncrypted) R.drawable.ic_lock
                        else R.drawable.ic_lock_open
                    )
                } else {
                    encryptionIndicator.visibility = View.GONE
                }
            }
        }

        // Apply keep screen awake preference
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val keepAwakeEnabled = prefs.getBoolean("keep_screen_awake", false)
        setKeepScreenAwake(keepAwakeEnabled)
    }

    override fun onResume() {
        super.onResume()
        // Ensure keep screen awake is always set according to preference
        val prefs = getSharedPreferences("user_prefs", MODE_PRIVATE)
        val keepAwakeEnabled = prefs.getBoolean("keep_screen_awake", false)
        setKeepScreenAwake(keepAwakeEnabled)
    }

    private fun setKeepScreenAwake(enabled: Boolean) {
        if (enabled) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}