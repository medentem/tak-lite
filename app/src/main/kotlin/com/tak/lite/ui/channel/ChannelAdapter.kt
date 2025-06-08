package com.tak.lite.ui.channel

import android.animation.AnimatorSet
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.data.model.IChannel
import com.tak.lite.data.model.MeshtasticChannel
import com.tak.lite.util.PositionPrecisionUtils

class ChannelAdapter(
    private val onGroupSelected: (IChannel) -> Unit,
    private val getUserName: (String) -> String = { it }, // Maps user ID to display name
    private val getIsActive: (IChannel) -> Boolean = { false }
) : ListAdapter<IChannel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {
    private val TAG = "ChannelAdapter"

    private var activeGroupId: String? = null
    private var receivingGroupId: String? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        Log.d(TAG, "Binding channel: ${channel.name} (${channel.id})")
        holder.bind(channel)
    }

    override fun submitList(list: List<IChannel>?) {
        Log.d(TAG, "Submitting new channel list: ${list?.map { "${it.name} (${it.id})" }}")
        super.submitList(list)
    }

    fun setActiveGroup(groupId: String?) {
        if (activeGroupId != groupId) {
            Log.d(TAG, "Setting active group from $activeGroupId to $groupId")
            val oldPosition = currentList.indexOfFirst { it.id == activeGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            activeGroupId = groupId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    fun setReceivingGroup(groupId: String?) {
        if (receivingGroupId != groupId) {
            Log.d(TAG, "Setting receiving group from $receivingGroupId to $groupId")
            val oldPosition = currentList.indexOfFirst { it.id == receivingGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            receivingGroupId = groupId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val channelInfo: TextView = itemView.findViewById(R.id.channelInfo)
        private val recentMessage: TextView = itemView.findViewById(R.id.recentMessage)
        private val audioIndicator: ImageView = itemView.findViewById(R.id.audioIndicator)
        private val activeChannelIndicator: View = itemView.findViewById(R.id.activeChannelIndicator)

        fun bind(channel: IChannel) {
            Log.d(TAG, "Binding channel view for: ${channel.name} (${channel.id})")
            
            // Set channel name
            channelName.text = channel.name

            // Set channel info (role and precision)
            val infoText = buildString {
                if (channel is MeshtasticChannel) {
                    append(when (channel.role) {
                        MeshtasticChannel.ChannelRole.PRIMARY -> "Primary"
                        MeshtasticChannel.ChannelRole.SECONDARY -> "Secondary"
                        MeshtasticChannel.ChannelRole.DISABLED -> "Disabled"
                    })
                }
                // Add precision info if available
                channel.precision?.let { precision ->
                    PositionPrecisionUtils.formatPrecision(precision)?.let { formattedPrecision ->
                        append(" • $formattedPrecision")
                    }
                }
            }
            channelInfo.text = infoText

            // Set recent message if available
            channel.lastMessage?.let { message ->
                recentMessage.text = "${message.senderShortName}: ${message.content}"
            } ?: run {
                recentMessage.text = "No messages"
            }

            // Handle active state - use getIsActive callback to determine if this channel is active
            val isActive = getIsActive(channel)
            Log.d(TAG, "Channel ${channel.name} (${channel.id}) active state: $isActive")
            activeChannelIndicator.visibility = if (isActive) View.VISIBLE else View.GONE

            // Handle audio indicator
            audioIndicator.visibility = if (channel.id == receivingGroupId) View.VISIBLE else View.GONE

            itemView.setOnClickListener {
                Log.d(TAG, "Channel clicked: ${channel.name} (${channel.id})")
                onGroupSelected(channel)
            }
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