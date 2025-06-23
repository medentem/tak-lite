package com.tak.lite.ui.message

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.R
import com.tak.lite.data.model.ChannelMessage
import com.tak.lite.data.model.MessageStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MessageAdapter(
    private val currentUserShortName: String?
) : ListAdapter<ChannelMessage, MessageAdapter.MessageViewHolder>(MessageDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MessageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_message, parent, false)
        return MessageViewHolder(view, currentUserShortName)
    }

    override fun onBindViewHolder(holder: MessageViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class MessageViewHolder(
        itemView: View,
        private val currentUserShortName: String?
    ) : RecyclerView.ViewHolder(itemView) {
        private val senderName: TextView = itemView.findViewById(R.id.senderName)
        private val messageContent: TextView = itemView.findViewById(R.id.messageContent)
        private val messageTime: TextView = itemView.findViewById(R.id.messageTime)
        private val statusCheck1: ImageView = itemView.findViewById(R.id.statusCheck1)
        private val statusCheck2: ImageView = itemView.findViewById(R.id.statusCheck2)
        private val statusCheck3: ImageView = itemView.findViewById(R.id.statusCheck3)
        private val messageStatusLayout: LinearLayout = itemView.findViewById(R.id.messageStatusLayout)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val constraintLayout: ConstraintLayout = itemView as ConstraintLayout
        private val constraintSet = ConstraintSet()

        fun bind(message: ChannelMessage) {
            senderName.text = message.senderShortName
            messageContent.text = message.content
            messageTime.text = dateFormat.format(Date(message.timestamp))

            // Clone the current constraints
            constraintSet.clone(constraintLayout)

            // Check if this is our message
            val isOurMessage = message.senderShortName == currentUserShortName
            
            messageStatusLayout.visibility = if (isOurMessage) View.VISIBLE else View.GONE

            if (isOurMessage) {
                // Align to the right for our messages
                constraintSet.clear(R.id.senderName, ConstraintSet.START)
                constraintSet.connect(R.id.senderName, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                
                constraintSet.clear(R.id.messageTime, ConstraintSet.START)
                constraintSet.connect(R.id.messageTime, ConstraintSet.END, R.id.senderName, ConstraintSet.START)
                constraintSet.setMargin(R.id.messageTime, ConstraintSet.END, 8)
                
                constraintSet.clear(R.id.messageContent, ConstraintSet.START)
                constraintSet.connect(R.id.messageContent, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

                messageContent.setBackgroundResource(R.drawable.message_background_sent)

                val isDirectMessage = message.channelId.startsWith("dm_")
                statusCheck3.visibility = if (isDirectMessage) View.VISIBLE else View.GONE

                // Reset all to outline
                statusCheck1.setImageResource(R.drawable.ic_check_circle_outline)
                statusCheck2.setImageResource(R.drawable.ic_check_circle_outline)
                statusCheck3.setImageResource(R.drawable.ic_check_circle_outline)
                statusCheck1.setColorFilter(null)
                statusCheck2.setColorFilter(null)
                statusCheck3.setColorFilter(null)

                // Fill icons based on status
                when (message.status) {
                    MessageStatus.SENT -> {
                        statusCheck1.setImageResource(R.drawable.ic_check_circle_filled)
                    }
                    MessageStatus.DELIVERED -> {
                        statusCheck1.setImageResource(R.drawable.ic_check_circle_filled)
                        statusCheck2.setImageResource(
                            if (isDirectMessage) R.drawable.ic_check_circle_filled else R.drawable.ic_check_circle_filled_green
                        )
                    }
                    MessageStatus.RECEIVED -> {
                        statusCheck1.setImageResource(R.drawable.ic_check_circle_filled)
                        statusCheck2.setImageResource(R.drawable.ic_check_circle_filled)
                        statusCheck3.setImageResource(R.drawable.ic_check_circle_filled_green)
                    }
                    else -> { /* keep all outline */ }
                }

                // Set click listeners for popover
                statusCheck1.setOnClickListener { showStatusPopover(itemView, isDirectMessage, message.status) }
                statusCheck2.setOnClickListener { showStatusPopover(itemView, isDirectMessage, message.status) }
                if (isDirectMessage) {
                    statusCheck3.setOnClickListener { showStatusPopover(itemView, isDirectMessage, message.status) }
                } else {
                    statusCheck3.setOnClickListener(null)
                }
            } else {
                // Align to the left for received messages
                constraintSet.clear(R.id.senderName, ConstraintSet.END)
                constraintSet.connect(R.id.senderName, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                
                constraintSet.clear(R.id.messageTime, ConstraintSet.END)
                constraintSet.connect(R.id.messageTime, ConstraintSet.START, R.id.senderName, ConstraintSet.END)
                
                constraintSet.clear(R.id.messageContent, ConstraintSet.END)
                constraintSet.connect(R.id.messageContent, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)

                messageContent.setBackgroundResource(R.drawable.message_background_received)
            }

            // Apply the constraints
            constraintSet.applyTo(constraintLayout)
        }

        private fun showStatusPopover(view: View, isDirectMessage: Boolean, status: MessageStatus) {
            var text = "";
            when (status) {
                MessageStatus.SENT -> {
                    text = "Message sent to the network"
                }
                MessageStatus.DELIVERED -> {
                    text = "Message received by the network"
                    if (isDirectMessage) {
                        text += " but not confirmed by target recipient"
                    }
                }
                MessageStatus.RECEIVED -> {
                    text = "Message confirmed received by the target recipient"
                }
                else -> { /* keep all outline */ }
            }
            android.widget.Toast.makeText(view.context, text, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<ChannelMessage>() {
        override fun areItemsTheSame(oldItem: ChannelMessage, newItem: ChannelMessage): Boolean {
            return oldItem.timestamp == newItem.timestamp && 
                   oldItem.senderShortName == newItem.senderShortName &&
                   oldItem.requestId == newItem.requestId
        }

        override fun areContentsTheSame(oldItem: ChannelMessage, newItem: ChannelMessage): Boolean {
            return oldItem == newItem
        }
    }
} 