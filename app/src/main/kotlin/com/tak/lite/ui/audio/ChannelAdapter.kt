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
import com.tak.lite.data.model.Channel

class ChannelAdapter(
    private val onGroupSelected: (Channel) -> Unit,
    private val getUserName: (String) -> String = { it }, // Maps user ID to display name
    private val getIsActive: (Channel) -> Boolean = { false }
) : ListAdapter<Channel, ChannelAdapter.ChannelViewHolder>(ChannelDiffCallback()) {

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
        holder.bind(getItem(position))
    }

    fun setActiveGroup(groupId: String?) {
        if (activeGroupId != groupId) {
            val oldPosition = currentList.indexOfFirst { it.id == activeGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            activeGroupId = groupId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    fun setTransmittingGroup(groupId: String?) {
        if (transmittingGroupId != groupId) {
            val oldPosition = currentList.indexOfFirst { it.id == transmittingGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            transmittingGroupId = groupId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    fun setReceivingGroup(groupId: String?) {
        if (receivingGroupId != groupId) {
            val oldPosition = currentList.indexOfFirst { it.id == receivingGroupId }
            val newPosition = currentList.indexOfFirst { it.id == groupId }
            receivingGroupId = groupId
            if (oldPosition != -1) notifyItemChanged(oldPosition)
            if (newPosition != -1) notifyItemChanged(newPosition)
        }
    }

    inner class ChannelViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val groupName: TextView = itemView.findViewById(R.id.groupName)
        private val memberCount: TextView = itemView.findViewById(R.id.groupMemberCount)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        private val membersList: TextView = itemView.findViewById(R.id.membersList)
        private val groupIndicator: ImageView = itemView.findViewById(R.id.groupIndicator)

        fun bind(group: Channel) {
            groupName.text = group.name
            memberCount.text = "${group.members.size} members"

            val isActive = group.id == activeGroupId || getIsActive(group)
            groupIndicator.visibility = if (isActive) View.VISIBLE else View.GONE

            val shouldAnimate = group.id == transmittingGroupId || group.id == receivingGroupId
            if (shouldAnimate) {
                if (animators[group.id] == null) {
                    val animator = AnimatorSet()
                    animator.playTogether(
                        android.animation.ObjectAnimator.ofFloat(groupIndicator, "scaleX", 1f, 1.2f).apply {
                            duration = 1000
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.REVERSE
                        },
                        android.animation.ObjectAnimator.ofFloat(groupIndicator, "scaleY", 1f, 1.2f).apply {
                            duration = 1000
                            repeatCount = android.animation.ValueAnimator.INFINITE
                            repeatMode = android.animation.ValueAnimator.REVERSE
                        }
                    )
                    animator.start()
                    animators[group.id] = animator
                }
            } else {
                animators[group.id]?.cancel()
                animators.remove(group.id)
                groupIndicator.scaleX = 1f
                groupIndicator.scaleY = 1f
            }

            val isExpanded = expandedGroups.contains(group.id)
            expandIcon.rotation = if (isExpanded) 90f else 0f
            membersList.visibility = if (isExpanded) View.VISIBLE else View.GONE
            membersList.text = group.members.joinToString(separator = "\n") { getUserName(it) }

            itemView.setOnClickListener {
                onGroupSelected(group)
            }
            expandIcon.setOnClickListener {
                if (isExpanded) expandedGroups.remove(group.id) else expandedGroups.add(group.id)
                notifyItemChanged(adapterPosition)
            }
        }
    }

    private class ChannelDiffCallback : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel): Boolean {
            return oldItem == newItem
        }
    }
} 