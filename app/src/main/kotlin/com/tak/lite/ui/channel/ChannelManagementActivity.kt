package com.tak.lite.ui.channel

import android.os.Bundle
import android.view.View
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
        val editText = EditText(this)
        editText.hint = "Channel name"
        MaterialAlertDialogBuilder(this)
            .setTitle("Add Channel")
            .setView(editText)
            .setPositiveButton("Add") { _, _ ->
                val name = editText.text.toString().trim()
                if (name.isNotEmpty()) viewModel.createChannel(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditChannelDialog(channel: IChannel) {
        val editText = EditText(this)
        editText.setText(channel.name)
        editText.setSelection(channel.name.length)
        MaterialAlertDialogBuilder(this)
            .setTitle("Edit Channel")
            .setView(editText)
            .setPositiveButton("Save") { _, _ ->
                val newName = editText.text.toString().trim()
                if (newName.isNotEmpty() && newName != channel.name) {
                    // For now, delete and re-create (since no rename in model)
                    viewModel.deleteChannel(channel.id)
                    viewModel.createChannel(newName)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteChannelDialog(channel: IChannel) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Channel")
            .setMessage("Are you sure you want to delete channel '${channel.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                viewModel.deleteChannel(channel.id)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
} 