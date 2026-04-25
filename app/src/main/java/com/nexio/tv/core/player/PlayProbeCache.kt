package com.nexio.tv.core.player

/**
 * Process-wide cache for the FFmpeg metadata probe result of the *current play*.
 *
 * One play needs that result in three places — DV gate, ASS/SSA pipeline init,
 * AFR detection — and previously each one called [FfmpegStreamMetadataProbe.probe]
 * independently, relying on the underlying LRU for de-duplication. This layer
 * makes the sharing explicit: whoever probes first stores the parsed result
 * here keyed by the original (pre-resolver-substitution) URL and headers, and
 * the later consumers read it without going through the probe layer at all.
 *
 * Bounded memory: small LRU + short TTL means a play that never finishes its
 * downstream consumers (e.g. user backs out before the player initializes)
 * cannot accumulate stale entries.
 */
object PlayProbeCache {
    private const val MAX_ENTRIES = 4
    private const val TTL_MS = 30_000L

    private data class Key(val url: String, val headersBlob: String?)
    private data class Entry(
        val metadata: FfmpegStreamMetadataProbeResult,
        val storedAtMs: Long
    )

    private val lock = Any()
    private val entries: LinkedHashMap<Key, Entry> =
        object : LinkedHashMap<Key, Entry>(MAX_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Entry>?): Boolean {
                return size > MAX_ENTRIES
            }
        }

    @Volatile
    private var clockOverride: (() -> Long)? = null

    fun put(
        url: String,
        headers: Map<String, String>?,
        metadata: FfmpegStreamMetadataProbeResult
    ) {
        val key = Key(url, headers.toBlob())
        synchronized(lock) {
            entries[key] = Entry(metadata, currentTimeMs())
        }
    }

    fun get(
        url: String,
        headers: Map<String, String>?
    ): FfmpegStreamMetadataProbeResult? {
        val key = Key(url, headers.toBlob())
        val now = currentTimeMs()
        return synchronized(lock) {
            val entry = entries[key] ?: return@synchronized null
            if (now - entry.storedAtMs > TTL_MS) {
                entries.remove(key)
                null
            } else {
                entry.metadata
            }
        }
    }

    internal fun setClockForTesting(clock: (() -> Long)?) {
        clockOverride = clock
    }

    internal fun resetForTesting() {
        synchronized(lock) { entries.clear() }
        clockOverride = null
    }

    private fun currentTimeMs(): Long = clockOverride?.invoke() ?: System.currentTimeMillis()

    private fun Map<String, String>?.toBlob(): String? {
        if (this.isNullOrEmpty()) return null
        return entries
            .asSequence()
            .filterNot { it.key.equals("Range", ignoreCase = true) }
            .sortedBy { it.key.lowercase() }
            .joinToString(separator = "\n") { "${it.key}: ${it.value}" }
    }
}
