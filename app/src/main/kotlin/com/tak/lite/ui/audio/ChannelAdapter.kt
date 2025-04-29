package com.tak.lite.ui.audio

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.data.model.AudioChannel

class ChannelAdapter(
    private val onChannelSelected: (AudioChannel) -> Unit,
    private val onChannelDeleted: (AudioChannel) -> Unit
) : ListAdapter<AudioChannel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val memberCount: TextView = itemView.findViewById(R.id.memberCount)
        private val defaultIndicator: View = itemView.findViewById(R.id.defaultIndicator)

        fun bind(channel: AudioChannel) {
            channelName.text = channel.name
            memberCount.text = "${channel.members.size} members"
            defaultIndicator.visibility = if (channel.isDefault) View.VISIBLE else View.GONE

            itemView.setOnClickListener { onChannelSelected(channel) }
            itemView.setOnLongClickListener {
                if (!channel.isDefault) {
                    onChannelDeleted(channel)
                }
                true
            }
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