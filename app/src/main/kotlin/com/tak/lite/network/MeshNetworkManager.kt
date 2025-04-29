package com.tak.lite.network

interface MeshNetworkManager {
    fun sendAudioData(audioData: ByteArray, channelId: String)
    fun receiveAudioData(channelId: String): ByteArray?
    fun connect()
    fun disconnect()
} 