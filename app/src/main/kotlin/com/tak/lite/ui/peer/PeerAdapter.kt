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
) : ListAdapter<MeshPeer, RecyclerView.ViewHolder>(PeerDiffCallback()) {

    private var onDividerClick: (() -> Unit)? = null
    private var showOlderPeers = false

    companion object {
        private const val VIEW_TYPE_PEER = 0
        private const val VIEW_TYPE_DIVIDER = 1
        private const val HOURS_24 = 24L
    }

    override fun getItemViewType(position: Int): Int {
        return if (position == getItemCount() - 1) VIEW_TYPE_DIVIDER else VIEW_TYPE_PEER
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_PEER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_peer, parent, false)
                PeerViewHolder(view)
            }
            VIEW_TYPE_DIVIDER -> {
                val view = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_hidden_peers_divider, parent, false)
                DividerViewHolder(view)
            }
            else -> throw IllegalArgumentException("Unknown view type")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is PeerViewHolder -> {
                val peer = getItem(position)
                holder.bind(peer)
            }
            is DividerViewHolder -> {
                val olderPeers = getOlderPeers()
                holder.bind(olderPeers.size, showOlderPeers)
            }
        }
    }

    override fun submitList(list: List<MeshPeer>?) {
        val sortedList = list?.sortedByDescending { it.lastSeen }
        super.submitList(sortedList)
    }

    fun setOnDividerClickListener(listener: () -> Unit) {
        onDividerClick = listener
    }

    fun toggleOlderPeers() {
        showOlderPeers = !showOlderPeers
        notifyDataSetChanged()
    }

    private fun getOlderPeers(): List<MeshPeer> {
        val currentTime = System.currentTimeMillis()
        return currentList.filter { peer ->
            val diffHours = TimeUnit.MILLISECONDS.toHours(currentTime - peer.lastSeen)
            diffHours >= HOURS_24
        }
    }

    override fun getItemCount(): Int {
        val olderPeers = getOlderPeers()
        val recentPeers = currentList.filter { peer ->
            val diffHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - peer.lastSeen)
            diffHours < HOURS_24
        }
        
        // If we have older peers, add 1 for the divider
        return if (olderPeers.isNotEmpty()) {
            if (showOlderPeers) {
                recentPeers.size + olderPeers.size + 1 // +1 for divider
            } else {
                recentPeers.size + 1 // +1 for divider
            }
        } else {
            recentPeers.size
        }
    }

    override fun getItem(position: Int): MeshPeer {
        val olderPeers = getOlderPeers()
        val recentPeers = currentList.filter { peer ->
            val diffHours = TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - peer.lastSeen)
            diffHours < HOURS_24
        }
        
        // If this is the divider position, throw exception
        if (olderPeers.isNotEmpty() && position == getItemCount() - 1) {
            throw IllegalStateException("Attempting to get peer at divider position")
        }
        
        // Calculate the maximum valid position for peers
        val maxPeerPosition = if (showOlderPeers) {
            recentPeers.size + olderPeers.size - 1
        } else {
            recentPeers.size - 1
        }
        
        // If position is beyond valid peer positions, throw exception
        if (position > maxPeerPosition) {
            throw IndexOutOfBoundsException("Position $position is out of bounds. Max valid position is $maxPeerPosition")
        }
        
        return if (position < recentPeers.size) {
            recentPeers[position]
        } else {
            olderPeers[position - recentPeers.size]
        }
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

    inner class DividerViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dividerText: TextView = itemView.findViewById(R.id.hiddenPeersText)
        private val dividerLayout: View = itemView.findViewById(R.id.hiddenPeersDivider)

        init {
            dividerLayout.setOnClickListener {
                onDividerClick?.invoke()
            }
        }

        fun bind(hiddenCount: Int, isExpanded: Boolean) {
            val text = if (isExpanded) {
                "Showing All Peers"
            } else {
                "$hiddenCount Peers Hidden"
            }
            dividerText.text = text
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