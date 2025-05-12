package com.tak.lite.ui.audio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.data.model.AudioChannel

class ChannelManagementAdapter(
    private val onEdit: (AudioChannel) -> Unit,
    private val onDelete: (AudioChannel) -> Unit,
    private val getIsActive: (AudioChannel) -> Boolean = { false }
) : ListAdapter<AudioChannel, ChannelManagementAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_management, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val memberCount: TextView = itemView.findViewById(R.id.memberCount)
        private val channelIndicator: ImageView = itemView.findViewById(R.id.channelIndicator)
        private val editButton: ImageButton = itemView.findViewById(R.id.editButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val defaultIndicator: View = itemView.findViewById(R.id.defaultIndicator)

        fun bind(channel: AudioChannel) {
            channelName.text = channel.name
            memberCount.text = "${channel.members.size} members"
            channelIndicator.visibility = if (getIsActive(channel)) View.VISIBLE else View.GONE
            defaultIndicator.visibility = if (channel.isDefault) View.VISIBLE else View.GONE
            editButton.visibility = if (channel.isDefault) View.GONE else View.VISIBLE
            deleteButton.visibility = if (channel.isDefault) View.GONE else View.VISIBLE
            editButton.setOnClickListener { onEdit(channel) }
            deleteButton.setOnClickListener { onDelete(channel) }
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<AudioChannel>() {
        override fun areItemsTheSame(oldItem: AudioChannel, newItem: AudioChannel): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: AudioChannel, newItem: AudioChannel): Boolean {
            return oldItem == newItem
        }
    }
} 