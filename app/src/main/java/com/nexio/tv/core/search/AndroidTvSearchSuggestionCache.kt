package com.nexio.tv.core.search

import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_SEARCH_CACHE_MAX_ENTRIES = 20
private const val DEFAULT_SEARCH_CACHE_TTL_MS = 2L * 60L * 1000L

@Singleton
class AndroidTvSearchSuggestionCache(
    private val maxEntries: Int = DEFAULT_SEARCH_CACHE_MAX_ENTRIES,
    private val ttlMs: Long = DEFAULT_SEARCH_CACHE_TTL_MS,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {
    @Inject
    constructor() : this(
        maxEntries = DEFAULT_SEARCH_CACHE_MAX_ENTRIES,
        ttlMs = DEFAULT_SEARCH_CACHE_TTL_MS,
        nowMs = { System.currentTimeMillis() }
    )

    private val entries = object : LinkedHashMap<CacheKey, CacheEntry>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CacheEntry>?): Boolean {
            return size > maxEntries.coerceAtLeast(1)
        }
    }

    suspend fun getOrPut(
        query: String,
        limit: Int,
        load: suspend () -> List<AndroidTvSearchSuggestion>
    ): List<AndroidTvSearchSuggestion> {
        val key = CacheKey(query = query.normalizedCacheQuery(), limit = limit.coerceAtLeast(1))
        val now = nowMs()
        synchronized(entries) {
            entries[key]?.takeIf { entry -> now - entry.createdAtMs <= ttlMs }?.let { entry ->
                return entry.suggestions
            }
            entries.remove(key)
        }

        val loaded = load()
        synchronized(entries) {
            entries[key] = CacheEntry(createdAtMs = nowMs(), suggestions = loaded)
        }
        return loaded
    }

    fun clear() {
        synchronized(entries) {
            entries.clear()
        }
    }

    private data class CacheKey(
        val query: String,
        val limit: Int
    )

    private data class CacheEntry(
        val createdAtMs: Long,
        val suggestions: List<AndroidTvSearchSuggestion>
    )

    private fun String.normalizedCacheQuery(): String = trim().lowercase()
}
