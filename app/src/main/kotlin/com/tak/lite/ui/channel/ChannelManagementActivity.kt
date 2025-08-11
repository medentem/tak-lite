package com.tak.lite.ui.channel

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tak.lite.R
import com.tak.lite.data.model.IChannel
import com.tak.lite.viewmodel.ChannelViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class ChannelManagementActivity : AppCompatActivity() {
    private val viewModel: ChannelViewModel by viewModels()
    private lateinit var adapter: ChannelManagementAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContentView(R.layout.fragment_channel_management)
        
        // Apply top inset to root layout
        val rootView = findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(insets.left, insets.top, insets.right, 0)
            WindowInsetsCompat.CONSUMED
        }
        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.channelManagementToolbar)
        setSupportActionBar(toolbar)
        toolbar.setNavigationOnClickListener { finish() }
        title = "Manage Channels"
        val recyclerView = findViewById<RecyclerView>(R.id.channelManagementList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = ChannelManagementAdapter(
            onEdit = { channel -> showEditChannelDialog(channel) },
            onDelete = { channel -> showDeleteChannelDialog(channel) },
            getIsActive = { channel -> viewModel.settings.value.selectedChannelId == channel.id }
        )
        recyclerView.adapter = adapter
        lifecycleScope.launch {
            viewModel.channels.collectLatest { channels ->
                adapter.submitList(channels)
            }
        }
        findViewById<MaterialButton>(R.id.addChannelButton)?.setOnClickListener {
            showAddChannelDialog()
        }
    }

    private fun showAddChannelDialog() {
        val editText = EditText(this).apply {
            hint = getString(R.string.channel_name_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        
        // Helper function to handle channel creation
        fun createChannel() {
            val name = editText.text.toString().trim()
            if (name.isNotEmpty()) viewModel.createChannel(name)
        }
        
        // Add editor action listener to handle Enter key
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                createChannel()
                true // Consume the event
            } else {
                false // Don't consume other events
            }
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.add_channel))
            .setView(editText)
            .setPositiveButton(getString(R.string.add)) { _, _ ->
                createChannel()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }

    private fun showEditChannelDialog(channel: IChannel) {
        val editText = EditText(this).apply {
            setText(channel.name)
            setSelection(channel.name.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        
        // Helper function to handle channel editing
        fun saveChannel() {
            val newName = editText.text.toString().trim()
            if (newName.isNotEmpty() && newName != channel.name) {
                // For now, delete and re-create (since no rename in model)
                viewModel.deleteChannel(channel.id)
                viewModel.createChannel(newName)
            }
        }
        
        // Add editor action listener to handle Enter key
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                saveChannel()
                true // Consume the event
            } else {
                false // Don't consume other events
            }
        }
        
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.edit_channel))
            .setView(editText)
            .setPositiveButton(getString(R.string.save)) { _, _ ->
                saveChannel()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        
        // Show the dialog and request focus for the EditText
        dialog.show()
        editText.requestFocus()
    }

    private fun showDeleteChannelDialog(channel: IChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.delete_channel))
            .setMessage(getString(R.string.delete_channel_confirmation, channel.name))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                viewModel.deleteChannel(channel.id)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }
} 