package com.tak.lite

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.mlkit.nl.smartreply.SmartReply
import com.google.mlkit.nl.smartreply.SmartReplySuggestion
import com.google.mlkit.nl.smartreply.SmartReplySuggestionResult
import com.google.mlkit.nl.smartreply.TextMessage
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.ui.message.MessageAdapter
import com.tak.lite.ui.util.EdgeToEdgeHelper
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
    private lateinit var smartReplyContainer: View
    private lateinit var smartReplyChipGroup: ChipGroup
    private lateinit var disabledOverlay: View
    private var channelId: String = ""
    private val smartReply = SmartReply.getClient()
    private val conversationHistory = mutableListOf<TextMessage>()

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
        smartReplyContainer = findViewById(R.id.smartReplyContainer)
        smartReplyChipGroup = findViewById(R.id.smartReplyChipGroup)
        disabledOverlay = findViewById(R.id.disabledOverlay)

        // Apply precise edge-to-edge insets: top on toolbar, bottom on input container
        EdgeToEdgeHelper.applyTopInsets(toolbar)
        val inputContainer = findViewById<View>(R.id.messageInputLayout)
        EdgeToEdgeHelper.applySidesInsets(inputContainer)
        EdgeToEdgeHelper.applyBottomInsets(inputContainer)

        // Setup RecyclerView
        // Get current user's short name from the protocol
        val currentUserId = viewModel.getCurrentUserId()
        adapter = MessageAdapter(currentUserId)
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
                // Clear smart reply suggestions after sending
                updateSmartReplySuggestions(emptyList())
            }
        }

        // Observe messages
        lifecycleScope.launch {
            viewModel.getMessages(channelId).collectLatest { messages ->
                adapter.submitList(messages)
                if (messages.isNotEmpty()) {
                    messageList.smoothScrollToPosition(messages.size - 1)
                    // Update conversation history for smart reply
                    updateConversationHistory(messages)
                }
            }
        }

        // Get channel info and update UI
        lifecycleScope.launch {
            Log.d("MessageActivity", "Starting to observe channel info for channelId: $channelId")
            viewModel.getChannelInfo(channelId).collectLatest { channelInfo ->
                Log.d("MessageActivity", "Received channel info update - name: ${channelInfo.name}, isPkiEncrypted: ${channelInfo.isPkiEncrypted}, readyToSend: ${channelInfo.readyToSend}")
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
                
                // Handle readyToSend status
                if (!channelInfo.readyToSend) {
                    // Show disabled overlay and disable input
                    disabledOverlay.visibility = View.VISIBLE
                    messageInput.isEnabled = false
                    sendButton.isEnabled = false
                    smartReplyContainer.visibility = View.GONE
                } else {
                    // Hide disabled overlay and enable input
                    disabledOverlay.visibility = View.GONE
                    messageInput.isEnabled = true
                    sendButton.isEnabled = true
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

    private fun updateConversationHistory(messages: List<ChannelMessage>) {
        conversationHistory.clear()
        messages.forEach { message ->
            val isLocalUser = message.senderShortName == viewModel.getCurrentUserShortName()
            val textMessage = if (isLocalUser) {
                TextMessage.createForLocalUser(message.content, message.timestamp)
            } else {
                TextMessage.createForRemoteUser(message.content, message.timestamp, message.senderShortName)
            }
            conversationHistory.add(textMessage)
        }
        generateSmartReplySuggestions()
    }

    private fun generateSmartReplySuggestions() {
        smartReply.suggestReplies(conversationHistory)
            .addOnSuccessListener { result ->
                if (result.status == SmartReplySuggestionResult.STATUS_SUCCESS) {
                    val suggestions = result.suggestions
                    updateSmartReplySuggestions(suggestions)
                } else {
                    updateSmartReplySuggestions(emptyList())
                }
            }
            .addOnFailureListener {
                Log.e("MessageActivity", "Error getting smart reply suggestions", it)
                updateSmartReplySuggestions(emptyList())
            }
    }

    private fun updateSmartReplySuggestions(suggestions: List<SmartReplySuggestion>) {
        smartReplyChipGroup.removeAllViews()
        if (suggestions.isEmpty()) {
            smartReplyContainer.visibility = View.GONE
            return
        }

        smartReplyContainer.visibility = View.VISIBLE
        suggestions.forEach { suggestion ->
            val chip = Chip(this).apply {
                text = suggestion.text
                isCheckable = false
                setOnClickListener {
                    messageInput.setText(suggestion.text)
                    messageInput.setSelection(suggestion.text.length)
                }
            }
            smartReplyChipGroup.addView(chip)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smartReply.close()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}