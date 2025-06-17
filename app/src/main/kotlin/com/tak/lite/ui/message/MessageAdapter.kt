package com.tak.lite.ui.message

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        private val messageStatus: TextView = itemView.findViewById(R.id.messageStatus)
        private val dateFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val constraintLayout: ConstraintLayout = itemView as ConstraintLayout
        private val constraintSet = ConstraintSet()

        fun bind(message: ChannelMessage) {
            senderName.text = message.senderShortName
            messageContent.text = message.content
            messageTime.text = dateFormat.format(Date(message.timestamp))
            
            // Set message status
            messageStatus.text = when (message.status) {
                MessageStatus.SENDING -> "Sending..."
                MessageStatus.SENT -> "Sent"
                MessageStatus.DELIVERED -> "Delivered"
                MessageStatus.RECEIVED -> "Received"
                MessageStatus.FAILED -> "Failed"
                MessageStatus.ERROR -> "Error"
            }
            messageStatus.visibility = View.VISIBLE

            // Clone the current constraints
            constraintSet.clone(constraintLayout)

            // Check if this is our message
            val isOurMessage = message.senderShortName == currentUserShortName
            
            if (isOurMessage) {
                // Align to the right for our messages
                constraintSet.clear(R.id.senderName, ConstraintSet.START)
                constraintSet.connect(R.id.senderName, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                
                constraintSet.clear(R.id.messageTime, ConstraintSet.START)
                constraintSet.connect(R.id.messageTime, ConstraintSet.END, R.id.senderName, ConstraintSet.START)
                constraintSet.setMargin(R.id.messageTime, ConstraintSet.END, 8)
                
                constraintSet.clear(R.id.messageContent, ConstraintSet.START)
                constraintSet.connect(R.id.messageContent, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                
                constraintSet.clear(R.id.messageStatus, ConstraintSet.START)
                constraintSet.connect(R.id.messageStatus, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
                
                messageContent.setBackgroundResource(R.drawable.message_background_sent)
            } else {
                // Align to the left for received messages
                constraintSet.clear(R.id.senderName, ConstraintSet.END)
                constraintSet.connect(R.id.senderName, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                
                constraintSet.clear(R.id.messageTime, ConstraintSet.END)
                constraintSet.connect(R.id.messageTime, ConstraintSet.START, R.id.senderName, ConstraintSet.END)
                
                constraintSet.clear(R.id.messageContent, ConstraintSet.END)
                constraintSet.connect(R.id.messageContent, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                
                constraintSet.clear(R.id.messageStatus, ConstraintSet.END)
                constraintSet.connect(R.id.messageStatus, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
                
                messageContent.setBackgroundResource(R.drawable.message_background_received)
            }

            // Apply the constraints
            constraintSet.applyTo(constraintLayout)
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