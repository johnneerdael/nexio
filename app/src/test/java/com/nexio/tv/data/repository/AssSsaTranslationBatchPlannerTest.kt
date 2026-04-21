package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class AssSsaTranslationBatchPlannerTest {
    @Test
    fun batchesByEventCount() {
        val units = (0 until 5).map { index ->
            AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize("Line $index")
            )
        }

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = units,
            config = AssSsaTranslationBatchConfig(
                maxEvents = 2,
                maxVisibleChars = 10_000,
                rampUpEnabled = false
            )
        )

        assertEquals(listOf(2, 2, 1), batches.map { it.coreCount })
    }

    @Test
    fun excludesPreserveOnlyUnitsFromProviderBatches() {
        val normal = AssSsaProtectedTranslationUnit.fromTokens(
            id = "normal",
            tokens = AssSsaTextTokenizer.tokenize("Hello")
        )
        val preserve = AssSsaProtectedTranslationUnit.fromTokens(
            id = "drawing",
            tokens = AssSsaTextTokenizer.tokenize("""{\p1}m 0 0 l 10 0{\p0}""")
        )

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = listOf(normal, preserve),
            config = AssSsaTranslationBatchConfig(
                maxEvents = 10,
                maxVisibleChars = 10_000,
                rampUpEnabled = false
            )
        )

        assertEquals(
            listOf(listOf("normal")),
            batches.map { batch -> batch.coreUnits.map { it.id } }
        )
    }

    @Test
    fun rampUpGrowsBatchSizes() {
        val units = (0 until 200).map { index ->
            AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize("Line $index")
            )
        }

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = units,
            config = AssSsaTranslationBatchConfig(maxEvents = 80, maxVisibleChars = 200_000)
        )

        // schedule [5,10,20,40,60] clipped to maxEvents=80, remainder is 200-5-10-20-40-60 = 65
        // so we expect [5, 10, 20, 40, 60, 60, 5]
        assertEquals(listOf(5, 10, 20, 40, 60, 60, 5), batches.map { it.coreCount })
    }

    @Test
    fun rampUpAddsOverlapBetweenAdjacentBatches() {
        val units = (0 until 20).map { index ->
            AssSsaProtectedTranslationUnit.fromTokens(
                id = "evt_$index",
                tokens = AssSsaTextTokenizer.tokenize("Line $index")
            )
        }

        val batches = AssSsaTranslationBatchPlanner.plan(
            units = units,
            config = AssSsaTranslationBatchConfig(maxEvents = 80, maxVisibleChars = 200_000)
        )

        // schedule [5,10,20,...] → core sizes [5, 10, 5]
        assertEquals(listOf(5, 10, 5), batches.map { it.coreCount })
        // first batch: no lead, 1 trail; middle: 1 lead + 1 trail; last: 1 lead + 0 trail
        assertEquals(listOf(0, 1, 1), batches.map { it.leadOverlap })
        assertEquals(listOf(1, 1, 0), batches.map { it.trailOverlap })
        // overlap cues match adjacent batches' boundary cues
        val coreLists = batches.map { it.coreUnits.map { u -> u.id } }
        val first = batches[0]
        val second = batches[1]
        val third = batches[2]
        assertEquals(coreLists[0].last(), second.units.first().id)
        assertEquals(coreLists[1].first(), first.units.last().id)
        assertEquals(coreLists[1].last(), third.units.first().id)
        assertEquals(coreLists[2].first(), second.units.last().id)
        // core cues are disjoint across batches and their union equals all input
        val allCore = coreLists.flatten()
        assertEquals(units.map { it.id }, allCore)
    }
}
