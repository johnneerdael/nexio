package com.nexio.tv.ui.screens.player

import java.util.HashMap
import java.util.LinkedHashMap
import java.util.TreeMap
import java.util.concurrent.atomic.AtomicLong

internal class StreamingRangeCoordinator : PlaybackFallbackRangeChecker {
    private val fallbackOwnedRegions = TreeMap<Long, LinkedHashMap<String, Region>>()
    private val coveredRegions = TreeMap<Long, CoveredRegion>()
    private val tokenIndex = HashMap<String, Region>()
    private val nextTokenId = AtomicLong(0L)
    private val lock = Any()

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        val normalizedStart = start.coerceAtLeast(0L)
        val normalizedEnd = endExclusive.coerceAtLeast(normalizedStart)
        val token = nextTokenId.incrementAndGet().toString()
        val region = Region(token, normalizedStart, normalizedEnd)

        synchronized(lock) {
            fallbackOwnedRegions.getOrPut(normalizedStart) {
                LinkedHashMap()
            }[token] = region
            tokenIndex[token] = region
            addCoveredRegion(region.start, region.endExclusive)
        }
        return token
    }

    fun clearFallbackOwnership(token: String) {
        synchronized(lock) {
            val region = tokenIndex.remove(token) ?: return
            fallbackOwnedRegions[region.start]?.let { regions ->
                regions.remove(token)
                if (regions.isEmpty()) {
                    fallbackOwnedRegions.remove(region.start)
                }
            }
            clearCoveredRegion(region)
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

    private fun addCoveredRegion(start: Long, endExclusive: Long) {
        if (endExclusive <= start) return

        var mergedStart = start
        var mergedEnd = endExclusive

        splitCoveredRegionAt(mergedStart)
        splitCoveredRegionAt(mergedEnd)

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

    private fun clearCoveredRegion(region: Region) {
        if (region.endExclusive <= region.start) return

        splitCoveredRegionAt(region.start)
        splitCoveredRegionAt(region.endExclusive)
        coveredRegions.subMap(region.start, true, region.endExclusive, false).clear()

        for (regionsAtStart in fallbackOwnedRegions.headMap(region.endExclusive, false).values) {
            for (activeRegion in regionsAtStart.values) {
                val overlapStart = maxOf(region.start, activeRegion.start)
                val overlapEnd = minOf(region.endExclusive, activeRegion.endExclusive)
                if (overlapEnd > overlapStart) {
                    addCoveredRegion(overlapStart, overlapEnd)
                }
            }
        }
    }

    private fun splitCoveredRegionAt(point: Long) {
        val floor = coveredRegions.floorEntry(point) ?: return
        val region = floor.value
        if (point <= region.start || point >= region.endExclusive) return

        coveredRegions.remove(floor.key)
        coveredRegions[region.start] = CoveredRegion(region.start, point)
        coveredRegions[point] = CoveredRegion(point, region.endExclusive)
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
