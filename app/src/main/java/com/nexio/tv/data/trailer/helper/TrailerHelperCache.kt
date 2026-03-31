package com.nexio.tv.data.trailer.helper

import com.nexio.tv.data.trailer.TrailerPlaybackSource
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val HELPER_NEGATIVE_CACHE_TTL: Duration = Duration.ofMinutes(10)
private val HELPER_POSITIVE_CACHE_TTL: Duration = Duration.ofHours(3)

private sealed interface CachedTrailerHelperLookup {
    data object Miss : CachedTrailerHelperLookup
    data class Hit(
        val source: TrailerPlaybackSource,
        val expiresAt: Instant
    ) : CachedTrailerHelperLookup
}

@Singleton
class TrailerHelperCache(
    private val clock: Clock
) {
    @Inject
    constructor() : this(clock = Clock.systemUTC())

    private val cache = ConcurrentHashMap<String, CachedTrailerHelperLookup>()
    private val negativeCache = ConcurrentHashMap<String, Instant>()

    fun get(key: String): TrailerPlaybackSource? {
        val now = Instant.now(clock)
        val hit = cache[key] as? CachedTrailerHelperLookup.Hit ?: return null
        if (hit.expiresAt.isBefore(now)) {
            cache.remove(key)
            return null
        }
        return hit.source
    }

    fun containsRecentMiss(key: String): Boolean {
        val now = Instant.now(clock)
        val expiresAt = negativeCache[key] ?: return false
        if (expiresAt.isBefore(now)) {
            negativeCache.remove(key)
            return false
        }
        return true
    }

    fun storeHit(
        key: String,
        source: TrailerPlaybackSource,
        expiresAtEpochMs: Long?
    ) {
        negativeCache.remove(key)
        val now = Instant.now(clock)
        val expiresAt = expiresAtEpochMs?.let { Instant.ofEpochMilli(it) }
            ?: now.plus(HELPER_POSITIVE_CACHE_TTL)
        cache[key] = CachedTrailerHelperLookup.Hit(source = source, expiresAt = expiresAt)
    }

    fun storeMiss(key: String) {
        cache.remove(key)
        negativeCache[key] = Instant.now(clock).plus(HELPER_NEGATIVE_CACHE_TTL)
    }
}
