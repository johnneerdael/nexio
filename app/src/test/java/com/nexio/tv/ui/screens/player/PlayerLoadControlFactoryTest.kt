package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

        assertTrue(loadControl is androidx.media3.exoplayer.DefaultLoadControl)
    }
}
