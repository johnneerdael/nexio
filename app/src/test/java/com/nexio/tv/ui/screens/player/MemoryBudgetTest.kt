package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryBudgetTest {
    @Test
    fun fillWorkerBudget_allowsOnePhase2Connection() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val budget = MemoryBudget(context)

        assertTrue(budget.fillWorkerWithinBudget(activeConnections = 1))
        assertTrue(budget.effectiveHeapBytes > 0)
        assertTrue(budget.effectiveSampleQueueBytes >= MemoryBudget.MIN_SAMPLE_QUEUE_BYTES)
    }

    @Test
    fun effectiveFillHorizonBytes_isBoundedBelowSampleQueueBudget() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val budget = MemoryBudget(context)

        assertTrue(budget.effectiveFillHorizonBytes >= 32L * 1024L * 1024L)
        assertTrue(budget.effectiveFillHorizonBytes <= 128L * 1024L * 1024L)
        assertTrue(budget.effectiveFillHorizonBytes <= budget.effectiveSampleQueueBytes)
    }

    @Test
    fun lowMemoryReduction_keepsFillHorizonWithinSampleQueueBudgetFloor() {
        val effectiveSampleQueueBytes = MemoryBudget.computeEffectiveSampleQueueBytes(
            sampleQueueBudgetBytes = MemoryBudget.MIN_SAMPLE_QUEUE_BYTES,
            hasRecentLowMemoryExit = true
        )
        val effectiveFillHorizonBytes = MemoryBudget.computeEffectiveFillHorizonBytes(
            effectiveSampleQueueBytes = effectiveSampleQueueBytes
        )

        assertTrue(effectiveFillHorizonBytes <= effectiveSampleQueueBytes)
    }
}
