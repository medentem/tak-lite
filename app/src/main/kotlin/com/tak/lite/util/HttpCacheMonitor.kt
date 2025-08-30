package com.tak.lite.util

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks OkHttp cache effectiveness over a sliding window without spamming logs.
 * Records host + whether a response was served purely from cache (cache hit).
 */
object HttpCacheMonitor {
    private const val TAG = "HttpCacheMonitor"
    private const val WINDOW_SIZE = 200
    private const val LOG_EVERY_N_EVENTS = 25

    enum class Category { CACHE_HIT, CONDITIONAL_HIT, NETWORK }

    private data class Event(val host: String, val category: Category)

    private val ring = ArrayDeque<Event>(WINDOW_SIZE)
    private val totalEvents = AtomicInteger(0)

    @Synchronized
    fun record(host: String, category: Category) {
        if (ring.size == WINDOW_SIZE) ring.removeFirst()
        ring.addLast(Event(host, category))
        val count = totalEvents.incrementAndGet()
        if (count % LOG_EVERY_N_EVENTS == 0) {
            logSummary()
        }
    }

    @Synchronized
    fun logSummary() {
        if (ring.isEmpty()) return
        data class Counts(var cache: Int = 0, var conditional: Int = 0, var network: Int = 0) {
            val total: Int get() = cache + conditional + network
        }
        var overall = Counts()
        val byHost = mutableMapOf<String, Counts>()
        for (e in ring) {
            val c = when (e.category) {
                Category.CACHE_HIT -> Counts(1, 0, 0)
                Category.CONDITIONAL_HIT -> Counts(0, 1, 0)
                Category.NETWORK -> Counts(0, 0, 1)
            }
            overall.cache += c.cache
            overall.conditional += c.conditional
            overall.network += c.network
            val hostCounts = byHost.getOrPut(e.host) { Counts() }
            hostCounts.cache += c.cache
            hostCounts.conditional += c.conditional
            hostCounts.network += c.network
        }
        val total = overall.total
        val overallCachePct = if (total > 0) (overall.cache * 100.0) / total else 0.0
        val overallCondPct = if (total > 0) (overall.conditional * 100.0) / total else 0.0
        val parts = byHost.entries
            .sortedByDescending { val h = it.value; if (h.total == 0) 0.0 else (h.cache * 100.0) / h.total }
            .joinToString { (host, h) ->
                val cachePct = if (h.total > 0) (h.cache * 100.0) / h.total else 0.0
                val condPct = if (h.total > 0) (h.conditional * 100.0) / h.total else 0.0
                "$host: cache=${"%.1f".format(cachePct)}% (${h.cache}/${h.total}), cond=${"%.1f".format(condPct)}% (${h.conditional}/${h.total})"
            }
        Log.d(TAG, "Cache over last $total (window $WINDOW_SIZE): cache=${"%.1f".format(overallCachePct)}% (${overall.cache}/$total), cond=${"%.1f".format(overallCondPct)}% (${overall.conditional}/$total) | $parts")
    }
}


