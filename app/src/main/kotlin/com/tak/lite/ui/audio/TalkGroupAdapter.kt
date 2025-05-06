package com.tak.lite.ui.audio

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

class TalkGroupAdapter(
    private val onGroupSelected: (AudioChannel) -> Unit,
    private val getUserName: (String) -> String = { it }, // Maps user ID to display name
    private val getIsActive: (AudioChannel) -> Boolean = { false }
) : ListAdapter<AudioChannel, TalkGroupAdapter.TalkGroupViewHolder>(TalkGroupDiffCallback()) {

    private val expandedGroups = mutableSetOf<String>()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TalkGroupViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_talk_group, parent, false)
        return TalkGroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: TalkGroupViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class TalkGroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val groupName: TextView = itemView.findViewById(R.id.groupName)
        private val memberCount: TextView = itemView.findViewById(R.id.groupMemberCount)
        private val expandIcon: ImageView = itemView.findViewById(R.id.expandIcon)
        private val membersList: TextView = itemView.findViewById(R.id.membersList)
        private val activeIndicator: View = itemView.findViewById(R.id.activeIndicator)

        fun bind(group: AudioChannel) {
            groupName.text = group.name
            memberCount.text = "${group.members.size} members"
            activeIndicator.visibility = if (getIsActive(group)) View.VISIBLE else View.INVISIBLE

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

    private class TalkGroupDiffCallback : DiffUtil.ItemCallback<AudioChannel>() {
        override fun areItemsTheSame(oldItem: AudioChannel, newItem: AudioChannel): Boolean {
            return oldItem.id == newItem.id
        }
        override fun areContentsTheSame(oldItem: AudioChannel, newItem: AudioChannel): Boolean {
            return oldItem == newItem
        }
    }
} 