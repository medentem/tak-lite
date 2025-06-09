package com.tak.lite.ui.message

import android.os.Bundle
import android.view.MenuItem
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tak.lite.R
import com.tak.lite.viewmodel.MessageViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MessageActivity : AppCompatActivity() {
    private val viewModel: MessageViewModel by viewModels()
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_message)

        // Get channel ID from intent
        val channelId = intent.getStringExtra("channel_id")
        if (channelId == null) {
            finish()
            return
        }

        // Setup toolbar
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        // Setup message list
        val messageList = findViewById<RecyclerView>(R.id.messageList)
        messageList.layoutManager = LinearLayoutManager(this).apply {
            stackFromEnd = true
        }
        
        // Get current user's short name from the protocol
        val currentUserShortName = viewModel.getCurrentUserShortName()
        adapter = MessageAdapter(currentUserShortName)
        messageList.adapter = adapter

        // Setup message input
        val messageInput = findViewById<TextInputEditText>(R.id.messageInput)
        val sendButton = findViewById<MaterialButton>(R.id.sendButton)

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

        // Set channel name as title
        lifecycleScope.launch {
            viewModel.getChannelName(channelId).collectLatest { name ->
                title = name
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