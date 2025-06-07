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

class ChannelAdapter(
    private val onGroupSelected: (IChannel) -> Unit,
    private val getUserName: (String) -> String = { it }, // Maps user ID to display name
    private val getIsActive: (IChannel) -> Boolean = { false }
) : ListAdapter<IChannel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {
    private val TAG = "ChannelAdapter"

    private val expandedGroups = mutableSetOf<String>()
    private var activeGroupId: String? = null
    private var transmittingGroupId: String? = null
    private var receivingGroupId: String? = null
    private val animators = mutableMapOf<String, AnimatorSet>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        val channel = getItem(position)
        Log.d(TAG, "Binding channel: ${channel.name}")
        holder.bind(channel)
    }

    override fun submitList(list: List<IChannel>?) {
        Log.d(TAG, "Submitting new channel list: ${list?.map { it.name }}")
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

    fun setTransmittingGroup(groupId: String?) {
        if (transmittingGroupId != groupId) {
            Log.d(TAG, "Setting transmitting group from $transmittingGroupId to $groupId")
            val oldPosition = currentList.indexOfFirst { it.id == transmittingGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            transmittingGroupId = groupId
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
        private val memberCount: TextView = itemView.findViewById(R.id.memberCount)
        private val channelIndicator: ImageView = itemView.findViewById(R.id.channelIndicator)
        private val defaultIndicator: View = itemView.findViewById(R.id.defaultIndicator)
        private val roleIndicator: TextView = itemView.findViewById(R.id.roleIndicator)

        fun bind(channel: IChannel) {
            Log.d(TAG, "Binding channel view for: ${channel.name}")
            channelName.text = channel.name
            memberCount.text = "${channel.members.size} members"
            val isActive = getIsActive(channel)
            Log.d(TAG, "Channel ${channel.name} active state: $isActive")
            channelIndicator.visibility = if (isActive) View.VISIBLE else View.GONE
            defaultIndicator.visibility = if (channel.isDefault) View.VISIBLE else View.GONE

            // Handle Meshtastic channel role display
            if (channel is MeshtasticChannel) {
                roleIndicator.visibility = View.VISIBLE
                roleIndicator.text = when (channel.role) {
                    MeshtasticChannel.ChannelRole.PRIMARY -> "Primary"
                    MeshtasticChannel.ChannelRole.SECONDARY -> "Secondary"
                    MeshtasticChannel.ChannelRole.DISABLED -> "Disabled"
                }
                Log.d(TAG, "Channel ${channel.name} role: ${channel.role}")
            } else {
                roleIndicator.visibility = View.GONE
            }

            // Handle transmitting/receiving animation
            val shouldAnimate = channel.id == transmittingGroupId || channel.id == receivingGroupId
            if (shouldAnimate) {
                if (animators[channel.id] == null) {
                    val animator = AnimatorSet()
                    animator.playTogether(
                        android.animation.ObjectAnimator.ofFloat(channelIndicator, "scaleX", 1f, 1.2f).apply {
                            duration = 1000
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.REVERSE
                        },
                        android.animation.ObjectAnimator.ofFloat(channelIndicator, "scaleY", 1f, 1.2f).apply {
                            duration = 1000
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.REVERSE
                        }
                    )
                    animator.start()
                    animators[channel.id] = animator
                }
            } else {
                animators[channel.id]?.cancel()
                animators.remove(channel.id)
                channelIndicator.scaleX = 1f
                channelIndicator.scaleY = 1f
            }

            itemView.setOnClickListener {
                Log.d(TAG, "Channel clicked: ${channel.name}")
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