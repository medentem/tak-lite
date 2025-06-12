package com.tak.lite.ui.peer

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.tak.lite.MessageActivity
import com.tak.lite.R
import com.tak.lite.network.MeshPeer
import com.tak.lite.viewmodel.MeshNetworkViewModel
import com.tak.lite.viewmodel.MessageViewModel
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

class PeerAdapter(
    private val onChatClick: (MeshPeer) -> Unit,
    private val messageViewModel: MessageViewModel,
    private val meshNetworkViewModel: MeshNetworkViewModel,
    private val lifecycleScope: CoroutineScope
) : ListAdapter<MeshPeer, PeerAdapter.PeerViewHolder>(PeerDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PeerViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_peer, parent, false)
        return PeerViewHolder(view)
    }

    override fun onBindViewHolder(holder: PeerViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    override fun submitList(list: List<MeshPeer>?) {
        val sortedList = list?.sortedByDescending { it.lastSeen }
        super.submitList(sortedList)
    }

    inner class PeerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val peerName: TextView = itemView.findViewById(R.id.peerName)
        private val peerLongName: TextView = itemView.findViewById(R.id.peerLongName)
        private val lastSeenTime: TextView = itemView.findViewById(R.id.lastSeenTime)
        private val lockIcon: ImageView = itemView.findViewById(R.id.lockIcon)
        private val chatButton: ImageButton = itemView.findViewById(R.id.chatButton)

        fun bind(peer: MeshPeer) {
            peerName.text = peer.nickname ?: peer.id
            peerLongName.text = peer.longName ?: peer.id
            
            // Format last seen time
            val lastSeenMillis = peer.lastSeen
            val currentTime = System.currentTimeMillis()
            val diffMillis = currentTime - lastSeenMillis
            
            val lastSeenText = when {
                diffMillis < TimeUnit.MINUTES.toMillis(1) -> "Last seen: just now"
                diffMillis < TimeUnit.HOURS.toMillis(1) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diffMillis)
                    "Last seen: $minutes min ago"
                }
                diffMillis < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diffMillis)
                    "Last seen: $hours hours ago"
                }
                else -> {
                    val days = TimeUnit.MILLISECONDS.toDays(diffMillis)
                    "Last seen: $days days ago"
                }
            }
            lastSeenTime.text = lastSeenText
            
            lockIcon.setImageResource(
                if (peer.hasPKC) R.drawable.ic_lock
                else R.drawable.ic_lock_open
            )
            chatButton.setOnClickListener {
                lifecycleScope.launch {
                    try {
                        // Get node info and create/get direct message channel
                        val nodeInfo = meshNetworkViewModel.getNodeInfo(peer.id)
                        val peerLongName = nodeInfo?.user?.longName
                        val channel = messageViewModel.getOrCreateDirectMessageChannel(peer.id, peerLongName)
                        Log.d("PeerAdapter", "Get or create direct message channel: ${channel.id} for peerId: ${peer.id}")
                        
                        // Launch MessageActivity with the channel ID
                        val intent = MessageActivity.createIntent(itemView.context, channel.id)
                        itemView.context.startActivity(intent)
                        onChatClick(peer)
                    } catch (e: Exception) {
                        Log.e("PeerAdapter", "Error starting direct message: ${e.message}", e)
                        Toast.makeText(itemView.context, "Failed to start direct message", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private class PeerDiffCallback : DiffUtil.ItemCallback<MeshPeer>() {
        override fun areItemsTheSame(oldItem: MeshPeer, newItem: MeshPeer): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: MeshPeer, newItem: MeshPeer): Boolean {
            return oldItem == newItem
        }
    }
} 