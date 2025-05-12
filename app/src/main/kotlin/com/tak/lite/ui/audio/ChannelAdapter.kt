package com.tak.lite.ui.audio

import android.animation.AnimatorSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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

    private var activeChannelId: String? = null
    private var transmittingChannelId: String? = null
    private var receivingChannelId: String? = null
    private val animators = mutableMapOf<String, AnimatorSet>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChannelViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel, parent, false)
        return ChannelViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChannelViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    fun setActiveChannel(channelId: String?) {
        if (activeChannelId != channelId) {
            val oldPosition = currentList.indexOfFirst { it.id == activeChannelId }
            val newPosition = currentList.indexOfFirst { it.id == channelId }
            activeChannelId = channelId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    fun setTransmittingChannel(channelId: String?) {
        if (transmittingChannelId != channelId) {
            val oldPosition = currentList.indexOfFirst { it.id == transmittingChannelId }
            val newPosition = currentList.indexOfFirst { it.id == channelId }
            transmittingChannelId = channelId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    fun setReceivingChannel(channelId: String?) {
        if (receivingChannelId != channelId) {
            val oldPosition = currentList.indexOfFirst { it.id == receivingChannelId }
            val newPosition = currentList.indexOfFirst { it.id == channelId }
            receivingChannelId = channelId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val channelName: TextView = itemView.findViewById(R.id.channelName)
        private val memberCount: TextView = itemView.findViewById(R.id.memberCount)
        private val defaultIndicator: View = itemView.findViewById(R.id.defaultIndicator)
        private val channelIndicator: ImageView = itemView.findViewById(R.id.channelIndicator)

        fun bind(channel: AudioChannel) {
            channelName.text = channel.name
            memberCount.text = "${channel.members.size} members"
            defaultIndicator.visibility = if (channel.isDefault) View.VISIBLE else View.GONE

            // Handle channel indicator
            val isActive = channel.id == activeChannelId
            channelIndicator.visibility = if (isActive) View.VISIBLE else View.GONE
            
            // Handle transmission/receiving animation
            val shouldAnimate = channel.id == transmittingChannelId || channel.id == receivingChannelId
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