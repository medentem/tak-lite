package com.tak.lite.network.takserver

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Imports Meshtastic Local TAK Server data packages (certs + prefs).
 */
data class TakServerConfig(
    val host: String = "127.0.0.1",
    val port: Int = 8089,
    val clientP12Path: String,
    val trustStoreP12Path: String,
    val clientPassword: String = TakDataPackage.DEFAULT_P12_PASSWORD,
    val trustPassword: String = TakDataPackage.DEFAULT_P12_PASSWORD
) {
    fun isReady(): Boolean =
        File(clientP12Path).isFile && File(trustStoreP12Path).isFile
}

object TakDataPackage {
    private const val TAG = "TakDataPackage"
    private const val PREFS = "tak_server_prefs"
    /** Meshtastic Local TAK Server bundled cert password (matches ATAK/iTAK package). */
    const val DEFAULT_P12_PASSWORD = "meshtastic"
    /** Common TAK / legacy fallbacks tried if the preferred password fails. */
    val PASSWORD_CANDIDATES = listOf("meshtastic", "atakatak", "")

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun loadConfig(context: Context): TakServerConfig? {
        val p = prefs(context)
        val client = p.getString("client_p12", null) ?: return null
        val trust = p.getString("trust_p12", null) ?: return null
        return TakServerConfig(
            host = p.getString("host", "127.0.0.1") ?: "127.0.0.1",
            port = p.getInt("port", 8089),
            clientP12Path = client,
            trustStoreP12Path = trust,
            clientPassword = p.getString("client_password", DEFAULT_P12_PASSWORD) ?: DEFAULT_P12_PASSWORD,
            trustPassword = p.getString("trust_password", DEFAULT_P12_PASSWORD) ?: DEFAULT_P12_PASSWORD
        )
    }

    fun hasImportedPackage(context: Context): Boolean =
        loadConfig(context)?.isReady() == true

    /**
     * Import a Meshtastic/ATAK data package zip. Persists PKCS12 files under app filesDir/takserver/.
     */
    fun importZip(context: Context, input: InputStream): TakServerConfig {
        val outDir = File(context.filesDir, "takserver").apply { mkdirs() }
        var clientFile: File? = null
        var trustFile: File? = null
        var host = "127.0.0.1"
        var port = 8089
        var clientPassword = DEFAULT_P12_PASSWORD
        var trustPassword = DEFAULT_P12_PASSWORD
        val prefTexts = mutableListOf<String>()

        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.substringAfterLast('/').lowercase()
                when {
                    name.endsWith(".p12") || name.endsWith(".pfx") -> {
                        val bytes = zis.readBytes()
                        val isClient = name.contains("client") || name.contains("user")
                        val isTrust = name.contains("trust") || name.contains("ca") ||
                            name == "truststore.p12" || name.contains("server")
                        val dest = when {
                            isClient && !isTrust -> File(outDir, "client.p12")
                            isTrust -> File(outDir, "truststore.p12")
                            clientFile == null -> File(outDir, "client.p12")
                            else -> File(outDir, "truststore.p12")
                        }
                        dest.writeBytes(bytes)
                        if (dest.name == "client.p12") clientFile = dest else trustFile = dest
                        Log.d(TAG, "Wrote cert ${dest.name} (${bytes.size} bytes) from $name")
                    }
                    name.endsWith(".pref") || name.endsWith(".xml") -> {
                        prefTexts.add(zis.readBytes().toString(Charsets.UTF_8))
                    }
                    else -> {
                        // drain unused entries
                        zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        for (text in prefTexts) {
            parsePref(text)?.let { (h, p, cp, tp) ->
                host = h
                port = p
                cp?.let { clientPassword = it }
                tp?.let { trustPassword = it }
            }
            // Explicit ATAK preference keys from Meshtastic packages
            Regex("""caPassword["']?\s*[^>]*>\s*([^<\s]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.let { trustPassword = it }
            Regex("""clientPassword["']?\s*[^>]*>\s*([^<\s]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.let { clientPassword = it }
            Regex("""key="caPassword"[^>]*>\s*([^<]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.let { trustPassword = it }
            Regex("""key="clientPassword"[^>]*>\s*([^<]+)""", RegexOption.IGNORE_CASE)
                .find(text)?.groupValues?.getOrNull(1)?.trim()?.let { clientPassword = it }
        }

        val client = clientFile ?: error("Data package missing client PKCS12")
        val trust = trustFile ?: client
        if (trustFile == null) {
            Log.w(TAG, "No separate truststore; using client.p12 as trust store")
        }

        // Verify passwords against the keystores; fall back through known candidates.
        clientPassword = resolvePassword(client, clientPassword, "client")
        trustPassword = resolvePassword(trust, trustPassword, "trust")

        val config = TakServerConfig(
            host = host,
            port = port,
            clientP12Path = client.absolutePath,
            trustStoreP12Path = trust.absolutePath,
            clientPassword = clientPassword,
            trustPassword = trustPassword
        )
        saveConfig(context, config)
        Log.i(TAG, "Imported TAK package host=$host:$port clientPwLen=${clientPassword.length} trustPwLen=${trustPassword.length}")
        return config
    }

    /** Try preferred password then known TAK defaults until PKCS12 loads. */
    fun resolvePassword(file: File, preferred: String, label: String): String {
        val tried = LinkedHashSet<String>()
        tried.add(preferred)
        tried.addAll(PASSWORD_CANDIDATES)
        for (pw in tried) {
            if (canLoadPkcs12(file, pw)) {
                if (pw != preferred) {
                    Log.i(TAG, "Resolved $label PKCS12 password via candidate (len=${pw.length})")
                }
                return pw
            }
        }
        Log.e(TAG, "Could not open $label PKCS12 with any known password: ${file.name}")
        return preferred
    }

    fun canLoadPkcs12(file: File, password: String): Boolean {
        return try {
            java.security.KeyStore.getInstance("PKCS12").apply {
                file.inputStream().use { load(it, password.toCharArray()) }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun saveConfig(context: Context, config: TakServerConfig) {
        prefs(context).edit()
            .putString("host", config.host)
            .putInt("port", config.port)
            .putString("client_p12", config.clientP12Path)
            .putString("trust_p12", config.trustStoreP12Path)
            .putString("client_password", config.clientPassword)
            .putString("trust_password", config.trustPassword)
            .apply()
    }

    private fun parsePref(xml: String): Quad? {
        return try {
            var host = "127.0.0.1"
            var port = 8089
            var clientPw: String? = null
            var trustPw: String? = null
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8")
            var event = parser.eventType
            var currentKey: String? = null
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "entry" -> currentKey = parser.getAttributeValue(null, "key")
                            "string", "int" -> {
                                // ATAK prefs sometimes nest values; also check attributes
                            }
                        }
                        // Common ATAK connectString / description patterns in attributes
                        for (i in 0 until parser.attributeCount) {
                            val an = parser.getAttributeName(i)
                            val av = parser.getAttributeValue(i)
                            when {
                                an.equals("connectString", true) || av.contains(":8089") -> {
                                    parseConnectString(av)?.let { (h, p) ->
                                        host = h
                                        port = p
                                    }
                                }
                                an.contains("password", true) -> {
                                    if (an.contains("trust", true) || an.contains("ca", true)) trustPw = av
                                    else clientPw = av
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty() && currentKey != null) {
                            when {
                                currentKey.contains("connectString", true) ||
                                    currentKey.contains("description", true) -> {
                                    parseConnectString(text)?.let { (h, p) ->
                                        host = h
                                        port = p
                                    }
                                }
                                currentKey.contains("password", true) -> {
                                    if (currentKey.contains("trust", true)) trustPw = text
                                    else clientPw = text
                                }
                            }
                        }
                    }
                }
                event = parser.next()
            }
            // Fallback: regex on raw XML for host:port
            if (host == "127.0.0.1") {
                parseConnectString(xml)?.let { (h, p) ->
                    host = h
                    port = p
                }
            }
            Quad(host, port, clientPw, trustPw)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse pref XML: ${e.message}")
            parseConnectString(xml)?.let { (h, p) -> Quad(h, p, null, null) }
        }
    }

    private fun parseConnectString(s: String): Pair<String, Int>? {
        // Formats: "127.0.0.1:8089:ssl" or "ssl://127.0.0.1:8089"
        val m1 = Regex("""(\d{1,3}(?:\.\d{1,3}){3}):(\d{2,5})""").find(s)
        if (m1 != null) {
            return m1.groupValues[1] to m1.groupValues[2].toInt()
        }
        val m2 = Regex("""localhost:(\d{2,5})""", RegexOption.IGNORE_CASE).find(s)
        if (m2 != null) {
            return "127.0.0.1" to m2.groupValues[1].toInt()
        }
        return null
    }

    private data class Quad(val host: String, val port: Int, val clientPw: String?, val trustPw: String?)
}
