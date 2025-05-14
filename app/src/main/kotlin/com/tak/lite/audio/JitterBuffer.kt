package com.tak.lite.audio

import android.util.Log
import java.util.concurrent.PriorityBlockingQueue
import kotlin.math.max
import kotlin.math.min

class JitterBuffer {
    companion object {
        private const val TAG = "JitterBuffer"
        private const val DEFAULT_BUFFER_SIZE = 5 // Number of packets to buffer
        private const val MAX_BUFFER_SIZE = 10
        private const val MIN_BUFFER_SIZE = 3
        private const val ADAPTATION_THRESHOLD = 0.1 // 10% packet loss threshold
    }

    data class AudioPacket(
        val data: ByteArray,
        val sequenceNumber: Long,
        val timestamp: Long
    ) : Comparable<AudioPacket> {
        override fun compareTo(other: AudioPacket): Int {
            return sequenceNumber.compareTo(other.sequenceNumber)
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AudioPacket
            return sequenceNumber == other.sequenceNumber
        }

        override fun hashCode(): Int {
            return sequenceNumber.hashCode()
        }
    }

    private val buffer = PriorityBlockingQueue<AudioPacket>(MAX_BUFFER_SIZE)
    private var nextSequenceNumber: Long = 0
    private var lastPlayedTimestamp: Long = 0
    private var currentBufferSize = DEFAULT_BUFFER_SIZE
    private var packetLossCount = 0
    private var totalPackets = 0

    fun addPacket(data: ByteArray, sequenceNumber: Long, timestamp: Long) {
        totalPackets++
        
        // Check for packet loss
        if (sequenceNumber > nextSequenceNumber + 1) {
            packetLossCount++
            Log.d(TAG, "Packet loss detected. Expected: ${nextSequenceNumber + 1}, Got: $sequenceNumber")
        }

        // Adapt buffer size based on packet loss
        adaptBufferSize()

        // Add packet to buffer
        buffer.offer(AudioPacket(data, sequenceNumber, timestamp))
    }

    fun getNextPacket(): ByteArray? {
        if (buffer.size < currentBufferSize) {
            return null // Wait for more packets
        }

        val packet = buffer.poll() ?: return null
        
        // Update sequence tracking
        nextSequenceNumber = packet.sequenceNumber + 1
        lastPlayedTimestamp = packet.timestamp

        return packet.data
    }

    private fun adaptBufferSize() {
        val lossRate = packetLossCount.toFloat() / totalPackets
        
        when {
            lossRate > ADAPTATION_THRESHOLD -> {
                // Increase buffer size to handle more packet loss
                currentBufferSize = min(currentBufferSize + 1, MAX_BUFFER_SIZE)
                Log.d(TAG, "Increasing buffer size to $currentBufferSize due to high packet loss")
            }
            lossRate < ADAPTATION_THRESHOLD / 2 -> {
                // Decrease buffer size for lower latency
                currentBufferSize = max(currentBufferSize - 1, MIN_BUFFER_SIZE)
                Log.d(TAG, "Decreasing buffer size to $currentBufferSize due to low packet loss")
            }
        }

        // Reset counters periodically
        if (totalPackets > 100) {
            packetLossCount = 0
            totalPackets = 0
        }
    }

    fun clear() {
        buffer.clear()
        nextSequenceNumber = 0
        lastPlayedTimestamp = 0
        currentBufferSize = DEFAULT_BUFFER_SIZE
        packetLossCount = 0
        totalPackets = 0
    }

    fun getBufferStats(): BufferStats {
        return BufferStats(
            currentSize = buffer.size,
            targetSize = currentBufferSize,
            packetLossRate = if (totalPackets > 0) packetLossCount.toFloat() / totalPackets else 0f
        )
    }

    data class BufferStats(
        val currentSize: Int,
        val targetSize: Int,
        val packetLossRate: Float
    )
} 