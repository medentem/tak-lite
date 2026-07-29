package com.tak.lite.network.takserver

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

/**
 * mTLS CoT client for Meshtastic Local TAK Server (default 127.0.0.1:8089).
 */
class TakServerTlsClient(
    private val config: TakServerConfig,
    private val onEvent: (String) -> Unit,
    private val onConnectionChanged: (Boolean, String?) -> Unit
) {
    private val TAG = "TakServerTlsClient"
    private val running = AtomicBoolean(false)
    private var socket: SSLSocket? = null
    private var readerThread: Thread? = null
    private var writerOut: BufferedOutputStream? = null
    private val writeLock = Any()

    fun isConnected(): Boolean = running.get() && socket?.isConnected == true && socket?.isClosed == false

    fun connect() {
        if (running.getAndSet(true)) return
        thread(name = "TakServerConnect", isDaemon = true) {
            try {
                val sslSocket = createSslSocket()
                sslSocket.soTimeout = 0
                sslSocket.connect(InetSocketAddress(config.host, config.port), 10_000)
                sslSocket.startHandshake()
                socket = sslSocket
                writerOut = BufferedOutputStream(sslSocket.outputStream)
                onConnectionChanged(true, null)
                Log.i(TAG, "Connected to ${config.host}:${config.port}")
                startReader(sslSocket)
            } catch (e: Exception) {
                Log.e(TAG, "Connect failed: ${e.message}", e)
                running.set(false)
                closeQuietly()
                onConnectionChanged(false, e.message ?: "Connection failed")
            }
        }
    }

    fun disconnect() {
        running.set(false)
        closeQuietly()
        onConnectionChanged(false, null)
    }

    fun send(xml: String): Boolean {
        if (!isConnected()) return false
        return try {
            val payload = if (xml.endsWith("\n")) xml.toByteArray(Charsets.UTF_8)
            else (xml + "\n").toByteArray(Charsets.UTF_8)
            synchronized(writeLock) {
                writerOut?.write(payload)
                writerOut?.flush()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Send failed: ${e.message}", e)
            false
        }
    }

    private fun createSslSocket(): SSLSocket {
        val clientPw = resolveWorkingPassword(config.clientP12Path, config.clientPassword)
        val trustPw = resolveWorkingPassword(config.trustStoreP12Path, config.trustPassword)

        val clientKs = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(config.clientP12Path).use { load(it, clientPw.toCharArray()) }
        }
        val trustKs = KeyStore.getInstance("PKCS12").apply {
            FileInputStream(config.trustStoreP12Path).use { load(it, trustPw.toCharArray()) }
        }
        // If truststore has no trusted cert entries, also try loading CA certs from aliases as certificates.
        ensureTrustEntries(trustKs)

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(clientKs, clientPw.toCharArray())
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustKs)
        }
        val ctx = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, tmf.trustManagers, null)
        }
        return ctx.socketFactory.createSocket() as SSLSocket
    }

    private fun resolveWorkingPassword(path: String, preferred: String): String {
        val file = java.io.File(path)
        if (TakDataPackage.canLoadPkcs12(file, preferred)) return preferred
        return TakDataPackage.resolvePassword(file, preferred, path.substringAfterLast('/'))
    }

    private fun ensureTrustEntries(trustKs: KeyStore) {
        var hasTrusted = false
        val aliases = trustKs.aliases()
        while (aliases.hasMoreElements()) {
            val alias = aliases.nextElement()
            if (trustKs.isCertificateEntry(alias) || trustKs.getCertificate(alias) != null) {
                hasTrusted = true
                // Promote key entries' certs into trusted cert entries for TrustManagerFactory
                if (!trustKs.isCertificateEntry(alias)) {
                    trustKs.getCertificate(alias)?.let { cert ->
                        trustKs.setCertificateEntry("$alias-trusted", cert)
                    }
                }
            }
        }
        if (!hasTrusted) {
            Log.w(TAG, "Trust store appears empty of certificates")
        }
    }

    private fun startReader(sslSocket: SSLSocket) {
        readerThread = thread(name = "TakServerReader", isDaemon = true) {
            val buffer = StringBuilder()
            try {
                BufferedInputStream(sslSocket.inputStream).use { input ->
                    val buf = ByteArray(4096)
                    while (running.get()) {
                        val n = input.read(buf)
                        if (n < 0) break
                        buffer.append(String(buf, 0, n, Charsets.UTF_8))
                        while (true) {
                            val idx = buffer.indexOf("</event>")
                            if (idx < 0) break
                            val end = idx + "</event>".length
                            var start = buffer.lastIndexOf("<?xml", end)
                            if (start < 0) start = buffer.lastIndexOf("<event", end)
                            if (start < 0) start = 0
                            val xml = buffer.substring(start, end).trim()
                            buffer.delete(0, end)
                            // trim leading junk
                            while (buffer.isNotEmpty() && buffer[0].isWhitespace()) {
                                buffer.deleteCharAt(0)
                            }
                            if (xml.contains("<event")) {
                                try {
                                    onEvent(xml)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Event handler error", e)
                                }
                            }
                        }
                        // Prevent unbounded growth
                        if (buffer.length > 512_000) {
                            Log.w(TAG, "CoT buffer overflow, clearing")
                            buffer.clear()
                        }
                    }
                }
            } catch (e: Exception) {
                if (running.get()) {
                    Log.w(TAG, "Reader ended: ${e.message}")
                }
            } finally {
                val wasRunning = running.getAndSet(false)
                closeQuietly()
                if (wasRunning) {
                    onConnectionChanged(false, "Disconnected")
                }
            }
        }
    }

    private fun closeQuietly() {
        try {
            writerOut?.close()
        } catch (_: Exception) {
        }
        writerOut = null
        try {
            socket?.close()
        } catch (_: Exception) {
        }
        socket = null
    }
}
