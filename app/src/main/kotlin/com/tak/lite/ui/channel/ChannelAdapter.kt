package com.tak.lite.ui.channel

import android.content.Intent
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
import com.tak.lite.data.model.MeshtasticChannel
import com.tak.lite.MessageActivity
import com.tak.lite.util.PositionPrecisionUtils

class ChannelAdapter(
    private val onGroupSelected: (IChannel) -> Unit,
    private val onDelete: (IChannel) -> Unit,
    private val getIsActive: (IChannel) -> Boolean = { false }
) : ListAdapter<IChannel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {
    private val TAG = "ChannelAdapter"

    private var receivingGroupId: String? = null
    private var itemInDeleteMode: Int = -1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        Log.d(TAG, "Binding channel: ${channel.name} (${channel.id})")
        holder.bind(channel, position == itemInDeleteMode)
    }

    override fun submitList(list: List<IChannel>?) {
        Log.d(TAG, "Submitting new channel list: ${list?.map { "${it.name} (${it.id})" }}")
        super.submitList(list)
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val channelInfo: TextView = itemView.findViewById(R.id.channelInfo)
        private val recentMessage: TextView = itemView.findViewById(R.id.recentMessage)
        private val audioIndicator: ImageView = itemView.findViewById(R.id.audioIndicator)
        private val activeChannelIndicator: View = itemView.findViewById(R.id.activeChannelIndicator)
        private val messageButton: ImageButton = itemView.findViewById(R.id.messageButton)
        private val deleteButton: ImageButton = itemView.findViewById(R.id.deleteButton)
        private val deleteBackground: View = itemView.findViewById(R.id.deleteBackground)

        fun bind(channel: IChannel, isInDeleteMode: Boolean) {
            Log.d(TAG, "Binding channel view for: ${channel.name} (${channel.id})")
            
            // Set channel name
            channelName.text = channel.displayName ?: channel.name

            // Set channel info (role and precision)
            val infoText = buildString {
                if (channel is MeshtasticChannel) {
                    append(when (channel.role) {
                        MeshtasticChannel.ChannelRole.PRIMARY -> itemView.context.getString(R.string.channel_role_primary)
                        MeshtasticChannel.ChannelRole.SECONDARY -> itemView.context.getString(R.string.channel_role_secondary)
                        MeshtasticChannel.ChannelRole.DISABLED -> itemView.context.getString(R.string.channel_role_disabled)
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
                recentMessage.text = itemView.context.getString(R.string.no_messages)
            }

            // Handle active state - use getIsActive callback to determine if this channel is active
            val isActive = getIsActive(channel)
            Log.d(TAG, "Channel ${channel.name} (${channel.id}) active state: $isActive")
            activeChannelIndicator.visibility = if (isActive) View.VISIBLE else View.GONE

            // Handle audio indicator
            audioIndicator.visibility = if (channel.id == receivingGroupId) View.VISIBLE else View.GONE

            // Handle message button click
            messageButton.setOnClickListener {
                val intent = Intent(itemView.context, MessageActivity::class.java).apply {
                    putExtra("channel_id", channel.id)
                }
                itemView.context.startActivity(intent)
            }

            // Handle delete mode
            if (channel.allowDelete) {
                Log.d(TAG, "Channel allows deletion, isInDeleteMode: $isInDeleteMode")
                if (isInDeleteMode) {
                    Log.d(TAG, "Showing delete UI for channel: ${channel.name}")
                    deleteBackground.visibility = View.VISIBLE
                    deleteButton.visibility = View.VISIBLE
                    // Animate the background sliding in from the right
                    deleteBackground.alpha = 0f
                    deleteBackground.animate()
                        .alpha(1f)
                        .setDuration(200)
                        .start()
                } else {
                    Log.d(TAG, "Hiding delete UI for channel: ${channel.name}")
                    // Animate the background sliding out to the right
                    deleteBackground.animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction {
                            deleteBackground.visibility = View.GONE
                            deleteButton.visibility = View.GONE
                        }
                        .start()
                }
            } else {
                Log.d(TAG, "Channel does not allow deletion: ${channel.name}")
                deleteBackground.visibility = View.GONE
                deleteButton.visibility = View.GONE
            }

            // Handle delete button
            deleteButton.setOnClickListener {
                onDelete(channel)
                itemInDeleteMode = -1
                notifyDataSetChanged()
            }

            // Handle long press for delete mode
            itemView.setOnLongClickListener {
                Log.d(TAG, "Long press detected on channel: ${channel.name} (${channel.id}), allowDelete: ${channel.allowDelete}")
                if (channel.allowDelete) {
                    itemInDeleteMode = if (itemInDeleteMode == adapterPosition) -1 else adapterPosition
                    Log.d(TAG, "Setting itemInDeleteMode to: $itemInDeleteMode")
                    notifyDataSetChanged()
                    true
                } else {
                    Log.d(TAG, "Channel does not allow deletion")
                    false
                }
            }

            // Handle normal click
            itemView.setOnClickListener {
                if (isInDeleteMode) {
                    // If in delete mode, just exit delete mode
                    itemInDeleteMode = -1
                    notifyDataSetChanged()
                    return@setOnClickListener
                }
                
                Log.d(TAG, "Channel clicked: ${channel.name} (${channel.id})")
                if (!channel.isSelectableForPrimaryTraffic) {
                    Log.d(TAG, "Channel ${channel.name} is not selectable for primary traffic")
                    return@setOnClickListener
                }
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