package com.tak.lite.ui.channel

import android.util.Log
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
import com.tak.lite.data.model.IChannel

class ChannelManagementAdapter(
    private val onEdit: (IChannel) -> Unit,
    private val onDelete: (IChannel) -> Unit,
    private val getIsActive: (IChannel) -> Boolean = { false }
) : ListAdapter<IChannel, ChannelManagementAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

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

        fun bind(channel: IChannel) {
            channelName.text = channel.name
            memberCount.text = "${channel.members.size} members"
            
            // Use getIsActive callback to determine if this channel is active
            val isActive = getIsActive(channel)
            Log.d("ChannelManagementAdapter", "Channel ${channel.name} active state: $isActive")
            channelIndicator.visibility = if (isActive) View.VISIBLE else View.GONE
            
            defaultIndicator.visibility = if (channel.isDefault) View.VISIBLE else View.GONE
            editButton.visibility = if (channel.isDefault) View.GONE else View.VISIBLE
            deleteButton.visibility = if (channel.isDefault) View.GONE else View.VISIBLE
            editButton.setOnClickListener { onEdit(channel) }
            deleteButton.setOnClickListener { onDelete(channel) }
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<IChannel>() {
        override fun areItemsTheSame(oldItem: IChannel, newItem: IChannel): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: IChannel, newItem: IChannel): Boolean {
            return oldItem == newItem
        }
    }
} 