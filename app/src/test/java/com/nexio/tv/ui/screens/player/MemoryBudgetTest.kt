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
}
