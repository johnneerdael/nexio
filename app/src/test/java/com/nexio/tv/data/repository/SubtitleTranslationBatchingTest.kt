package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationBatchingTest {

    @Test
    fun rampUpDisabledProducesSingleBatchRespectingMaxEntries() {
        val items = (0 until 150).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = false
        )

        assertEquals(listOf(60, 60, 30), batches.map { it.coreCount })
    }

    @Test
    fun rampUpMatchesSpecifiedSchedule() {
        val items = (0 until 200).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = true
        )

        // Expected: [5, 10, 20, 40, 60, 60, 5] — 200 = 5+10+20+40+60+60+5
        assertEquals(listOf(5, 10, 20, 40, 60, 60, 5), batches.map { it.coreCount })
    }

    @Test
    fun rampUpScheduleClampedByMaxCoreEntries() {
        // maxCoreEntries=30 should cap the schedule to [5, 10, 20, 30, 30]
        val items = (0 until 120).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 30,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = true
        )

        // Expected: [5, 10, 20, 30, 30, 25] = 120
        assertEquals(listOf(5, 10, 20, 30, 30, 25), batches.map { it.coreCount })
    }

    @Test
    fun overlapContextMatchesAdjacentBoundaryItems() {
        val items = (0 until 20).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = true
        )

        // core sizes with schedule [5,10,20,...]: [5, 10, 5]
        assertEquals(listOf(5, 10, 5), batches.map { it.coreCount })
        assertEquals(listOf(0, 1, 1), batches.map { it.leadOverlap })
        assertEquals(listOf(1, 1, 0), batches.map { it.trailOverlap })

        // first batch: [0..4] core, then 5 as overlap context
        assertEquals(listOf(0, 1, 2, 3, 4, 5), batches[0].items)
        assertEquals(listOf(0, 1, 2, 3, 4), batches[0].coreItems)

        // middle batch: [4] overlap, [5..14] core, [15] overlap
        assertEquals(listOf(4) + (5..14) + listOf(15), batches[1].items)
        assertEquals((5..14).toList(), batches[1].coreItems)

        // last batch: [14] overlap, [15..19] core
        assertEquals(listOf(14) + (15..19), batches[2].items)
        assertEquals((15..19).toList(), batches[2].coreItems)
    }

    @Test
    fun coreItemsAreDisjointAndCoverInput() {
        val items = (0 until 180).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = true
        )

        val allCore = batches.flatMap { it.coreItems }
        assertEquals(items, allCore)
        assertEquals(items.size, allCore.toSet().size)
    }

    @Test
    fun singleBatchHasNoOverlap() {
        val items = (0 until 3).toList()

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 1_000_000,
            sizeOf = { 1 },
            rampUpEnabled = true
        )

        assertEquals(1, batches.size)
        assertEquals(0, batches[0].leadOverlap)
        assertEquals(0, batches[0].trailOverlap)
        assertEquals(items, batches[0].coreItems)
    }

    @Test
    fun charLimitForcesFlushBeforeCountLimit() {
        // Each item weighs 100 chars. maxCoreChars=250 → flush after 2 items even though ramp-up allows 5.
        val items = List(10) { "x".repeat(100) }

        val batches = planRampedTranslationBatches(
            items = items,
            maxCoreEntries = 60,
            maxCoreChars = 250,
            sizeOf = { it.length },
            rampUpEnabled = true
        )

        // All batches should cap at 2 items per batch due to char limit
        assertTrue(batches.all { it.coreCount <= 2 })
        assertEquals(items.size, batches.sumOf { it.coreCount })
    }

    @Test
    fun emptyInputReturnsEmptyList() {
        val batches = planRampedTranslationBatches(
            items = emptyList<String>(),
            maxCoreEntries = 60,
            maxCoreChars = 1_000,
            sizeOf = { it.length }
        )
        assertEquals(emptyList<RampedTranslationBatch<String>>(), batches)
    }
}
