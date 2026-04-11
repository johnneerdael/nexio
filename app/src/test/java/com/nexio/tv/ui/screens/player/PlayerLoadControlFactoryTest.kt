package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLoadControlFactoryTest {

    @Test
    fun budgetedSpec_clampsTargetBufferToMemoryBudget_minimum() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 16L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertEquals(MemoryBudget.MIN_SAMPLE_QUEUE_BYTES.toInt(), spec.targetBufferBytes)
        assertFalse(spec.prioritizeTimeOverSizeThresholdsForStreaming)
        assertTrue(spec.maxBufferMs in 8_000..30_000)
    }

    @Test
    fun budgetedSpec_clampsTargetBufferToMemoryBudget_maximum() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 512L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertEquals(MemoryBudget.MAX_SAMPLE_QUEUE_BYTES.toInt(), spec.targetBufferBytes)
        assertFalse(spec.prioritizeTimeOverSizeThresholdsForStreaming)
        assertTrue(spec.maxBufferMs in 8_000..30_000)
    }

    @Test
    fun budgetedSpec_minBufferAlwaysSatisfiesPlaybackThresholds() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 32L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertTrue(spec.minBufferMs >= spec.bufferForPlaybackMs)
        assertTrue(spec.minBufferMs >= spec.bufferForPlaybackAfterRebufferMs)
        assertTrue(spec.maxBufferMs >= spec.minBufferMs)
    }

    @Test
    fun budgetedSpec_usesThirtySecondCapForLargeBudget() {
        val spec = PlayerLoadControlFactory.buildBudgetedSpec(
            effectiveSampleQueueBytes = 900L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertEquals(30_000, spec.maxBufferMs)
        assertEquals(15_000, spec.minBufferMs)
    }

    @Test
    fun buildBudgetedLoadControl_constructsMedia3LoadControl() {
        val loadControl = PlayerLoadControlFactory.buildBudgetedLoadControl(
            effectiveSampleQueueBytes = 128L * 1024L * 1024L
        )

        assertNotNull(loadControl)
    }

    @Test
    fun selectLoadControl_usesDefaultWhenStreamingCacheDisabled() {
        val selection = PlayerLoadControlFactory.selectLoadControl(
            streamingCacheEnabled = false,
            effectiveSampleQueueBytes = MemoryBudget.MAX_SAMPLE_QUEUE_BYTES,
            estimatedBitrateBps = 120_000_000L
        )

        assertSame(PlayerLoadControlFactory.LoadControlSelection.Default, selection)
    }

    @Test
    fun selectLoadControl_usesBudgetedSpecWhenStreamingCacheEnabled() {
        val selection = PlayerLoadControlFactory.selectLoadControl(
            streamingCacheEnabled = true,
            effectiveSampleQueueBytes = 128L * 1024L * 1024L,
            estimatedBitrateBps = 120_000_000L
        )

        assertTrue(selection is PlayerLoadControlFactory.LoadControlSelection.Budgeted)
        val budgeted = selection as PlayerLoadControlFactory.LoadControlSelection.Budgeted
        assertEquals(128L * 1024L * 1024L, budgeted.spec.targetBufferBytes.toLong())
        assertFalse(budgeted.spec.prioritizeTimeOverSizeThresholdsForStreaming)
    }

    @Test
    fun buildForStreamingCacheDecision_constructsLoadControlForBothBranches() {
        listOf(false, true).forEach { streamingCacheEnabled ->
            val loadControl = PlayerLoadControlFactory.buildForStreamingCacheDecision(
                streamingCacheEnabled = streamingCacheEnabled,
                effectiveSampleQueueBytes = 128L * 1024L * 1024L,
                estimatedBitrateBps = 120_000_000L
            )

            assertNotNull(loadControl)
        }
    }
}
