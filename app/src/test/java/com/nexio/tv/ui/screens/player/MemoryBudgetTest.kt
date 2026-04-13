package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
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
    fun `disk spool ram buffer is capped by heap budget`() {
        assertEquals(
            64L * 1024L * 1024L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 128,
                heapLimitBytes = 256L * 1024L * 1024L
            )
        )
    }

    @Test
    fun `disk spool ram buffer disables itself on very small heaps`() {
        assertEquals(
            0L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 64,
                heapLimitBytes = 48L * 1024L * 1024L
            )
        )
    }

    @Test
    fun `disk spool ram buffer can be disabled by setting`() {
        assertEquals(
            0L,
            MemoryBudget.effectiveDiskSpoolReadAheadBytes(
                requestedMb = 0,
                heapLimitBytes = 512L * 1024L * 1024L
            )
        )
    }
}
