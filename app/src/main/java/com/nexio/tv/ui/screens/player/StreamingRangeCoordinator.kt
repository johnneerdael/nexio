package com.nexio.tv.ui.screens.player

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

internal class StreamingRangeCoordinator {
    private val fallbackOwnedRegions = ConcurrentSkipListMap<Long, MutableMap<String, Region>>()
    private val tokenIndex = ConcurrentHashMap<String, Region>()

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        val token = UUID.randomUUID().toString()
        val region = Region(token, normalizedStart, normalizedEnd)

        fallbackOwnedRegions.compute(normalizedStart) { _, existing ->
            (existing ?: LinkedHashMap()).apply {
                put(token, region)
            }
        }
        tokenIndex[token] = region
        return token
    }

    fun clearFallbackOwnership(token: String) {
        val region = tokenIndex.remove(token) ?: return
        fallbackOwnedRegions.computeIfPresent(region.start) { _, regions ->
            regions.remove(token)
            if (regions.isEmpty()) null else regions
        }
    }

    fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        if (normalizedEnd <= normalizedStart) return false

        val floor = fallbackOwnedRegions.floorEntry(normalizedStart)
        if (floor?.value?.values?.any { it.endExclusive > normalizedStart } == true) return true

        val overlappingStarts = fallbackOwnedRegions.subMap(normalizedStart, true, normalizedEnd, false)
        return overlappingStarts.values.any { regions ->
            regions.values.any { it.endExclusive > normalizedStart }
        }
    }

    private data class Region(
        val token: String,
        val start: Long,
        val endExclusive: Long
    )
}
