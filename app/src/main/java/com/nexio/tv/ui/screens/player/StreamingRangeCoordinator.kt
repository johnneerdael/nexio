package com.nexio.tv.ui.screens.player

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

internal class StreamingRangeCoordinator : PlaybackFallbackRangeChecker {
    private val fallbackOwnedRegions = ConcurrentSkipListMap<Long, ConcurrentHashMap<String, Region>>()
    private val coveredRegions = ConcurrentSkipListMap<Long, CoveredRegion>()
    private val tokenIndex = ConcurrentHashMap<String, Region>()
    private val lock = Any()

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        val token = UUID.randomUUID().toString()
        val region = Region(token, normalizedStart, normalizedEnd)

        synchronized(lock) {
            fallbackOwnedRegions.compute(normalizedStart) { _, existing ->
                (existing ?: ConcurrentHashMap()).apply {
                    put(token, region)
                }
            }
            tokenIndex[token] = region
            addCoveredRegion(region)
        }
        return token
    }

    fun clearFallbackOwnership(token: String) {
        synchronized(lock) {
            val region = tokenIndex.remove(token) ?: return
            fallbackOwnedRegions.computeIfPresent(region.start) { _, regions ->
                regions.remove(token)
                if (regions.isEmpty()) null else regions
            }
            rebuildCoveredRegions()
        }
    }

    override fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        if (normalizedEnd <= normalizedStart) return false

        synchronized(lock) {
            val floor = coveredRegions.floorEntry(normalizedStart)
            if (floor?.value?.endExclusive?.let { it > normalizedStart } == true) return true

            val overlappingStarts = coveredRegions.subMap(normalizedStart, true, normalizedEnd, false)
            return overlappingStarts.values.any { it.endExclusive > normalizedStart }
        }
    }

    private fun addCoveredRegion(region: Region) {
        if (region.endExclusive <= region.start) return

        var mergedStart = region.start
        var mergedEnd = region.endExclusive

        val floor = coveredRegions.floorEntry(mergedStart)
        if (floor != null && floor.value.endExclusive > mergedStart) {
            mergedStart = floor.value.start
            mergedEnd = maxOf(mergedEnd, floor.value.endExclusive)
            coveredRegions.remove(floor.key)
        }

        var next = coveredRegions.ceilingEntry(mergedStart)
        while (next != null && next.key < mergedEnd) {
            mergedEnd = maxOf(mergedEnd, next.value.endExclusive)
            coveredRegions.remove(next.key)
            next = coveredRegions.ceilingEntry(mergedStart)
        }

        coveredRegions[mergedStart] = CoveredRegion(mergedStart, mergedEnd)
    }

    private fun rebuildCoveredRegions() {
        coveredRegions.clear()
        tokenIndex.values
            .sortedBy { it.start }
            .forEach(::addCoveredRegion)
    }

    private data class Region(
        val token: String,
        val start: Long,
        val endExclusive: Long
    )

    private data class CoveredRegion(
        val start: Long,
        val endExclusive: Long
    )
}
