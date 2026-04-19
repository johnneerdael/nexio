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
            config = AssSsaTranslationBatchConfig(maxEvents = 2, maxVisibleChars = 10_000)
        )

        assertEquals(listOf(2, 2, 1), batches.map { it.units.size })
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
            config = AssSsaTranslationBatchConfig(maxEvents = 10, maxVisibleChars = 10_000)
        )

        assertEquals(listOf(listOf("normal")), batches.map { batch -> batch.units.map { it.id } })
    }
}
