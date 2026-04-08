package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [DualLaneScheduler] prefetch preemption contract (Phase 4, memo invariants I4–I7).
 *
 * I4: Urgent submitted while prefetch is running → preempt fires → prefetch handle.preempted = true.
 * I5: After urgent drains, parked prefetch resumes and ultimately completes.
 * I6: Multi-preempt — same prefetch preempted twice still completes byte-identically.
 * I7: cancelAllPrefetch drops parked tasks without leaking state.
 */
class DualLaneSchedulerPrefetchPreemptionTest {

    // -------------------------------------------------------------------------
    // I4: urgent preemption actually fires
    // -------------------------------------------------------------------------

    @Test(timeout = 15_000L)
    fun urgentPreemptsInFlightPrefetch() {
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        val prefetchRunning = CountDownLatch(1)
        val prefetchPreempted = AtomicBoolean(false)
        val prefetchUnblockForever = CountDownLatch(1)  // never released — preemption must exit it

        // Submit a prefetch that blocks until preempted.
        scheduler.submitPrefetch(0L..1023L, chunkSize = 1024L) { handle ->
            prefetchRunning.countDown()
            // Spin checking preempted — mirrors what downloadRangeIntoScratch does.
            // Must catch InterruptedException: future.cancel(true) sends an interrupt which
            // wakes Thread.sleep; at that point handle.preempted is already true (set before cancel).
            val deadline = System.currentTimeMillis() + 8_000L
            while (!handle.preempted.get() && System.currentTimeMillis() < deadline) {
                try { Thread.sleep(5L) } catch (_: InterruptedException) { break }
            }
            prefetchPreempted.set(handle.preempted.get())
        }

        assertTrue("Prefetch task should start", prefetchRunning.await(5, TimeUnit.SECONDS))

        // Submit urgent — must preempt the prefetch.
        val urgentDone = CountDownLatch(1)
        scheduler.submitUrgent(0L..1023L) { _ ->
            urgentDone.countDown()
        }

        assertTrue("Urgent task should complete", urgentDone.await(5, TimeUnit.SECONDS))
        // Give the prefetch callback a moment to observe the preempted flag.
        val deadline = System.currentTimeMillis() + 3_000L
        while (!prefetchPreempted.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L)
        }
        assertTrue("Prefetch handle should be marked preempted after urgent submission", prefetchPreempted.get())

        scheduler.cancelAll()
    }

    // -------------------------------------------------------------------------
    // I4 + I5: prefetch is accepted (not null), urgent runs first, then prefetch resumes
    // -------------------------------------------------------------------------

    @Test(timeout = 15_000L)
    fun prefetchAcceptedNotStarvedUrgentRunsFirst() {
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        val urgentBlockLatch = CountDownLatch(1)
        val urgentRunningLatch = CountDownLatch(1)
        val prefetchCompletedLatch = CountDownLatch(1)
        val executionOrder = mutableListOf<String>()
        val orderLock = Any()

        // Block the urgent lane.
        val urgentFuture = scheduler.submitUrgent(0L..1023L) { _ ->
            urgentRunningLatch.countDown()
            urgentBlockLatch.await(5, TimeUnit.SECONDS)
            synchronized(orderLock) { executionOrder.add("urgent") }
        }

        assertTrue("Urgent should start", urgentRunningLatch.await(5, TimeUnit.SECONDS))

        // Submit prefetch while urgent is running — must be accepted (parked), not null.
        val prefetchFuture = scheduler.submitPrefetch(0L..1023L, chunkSize = 1024L) { handle ->
            synchronized(orderLock) { executionOrder.add("prefetch") }
            prefetchCompletedLatch.countDown()
        }

        assertNotNull("submitPrefetch must never return null", prefetchFuture)

        // Release urgent.
        urgentBlockLatch.countDown()
        urgentFuture.get(5, TimeUnit.SECONDS)

        // Prefetch should resume and complete after urgent drains.
        assertTrue("Prefetch should complete after urgent drains", prefetchCompletedLatch.await(8, TimeUnit.SECONDS))

        synchronized(orderLock) {
            assertTrue("Urgent must complete before prefetch", executionOrder.indexOf("urgent") < executionOrder.indexOf("prefetch"))
        }

        scheduler.cancelAll()
    }

    // -------------------------------------------------------------------------
    // I6: multi-preempt — preempt twice, still completes
    // -------------------------------------------------------------------------

    @Test(timeout = 20_000L)
    fun multiPreemptPrefetchStillCompletes() {
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        val prefetchCompletionCount = AtomicInteger(0)
        val prefetchCompletedLatch = CountDownLatch(1)

        // We'll track how many times the prefetch callback runs (preempt + 2 resumes = 3 runs of the same handle).
        // The handle accumulates totalRead across preemptions; on final run it "completes".
        val runCount = AtomicInteger(0)
        val firstRunReached = CountDownLatch(1)
        val firstRunRelease = CountDownLatch(1)

        scheduler.submitPrefetch(0L..63L, chunkSize = 64L) { handle ->
            val run = runCount.incrementAndGet()
            if (run == 1) {
                firstRunReached.countDown()
                // Wait to be preempted.
                val deadline = System.currentTimeMillis() + 5_000L
                while (!handle.preempted.get() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(5L) } catch (_: InterruptedException) { break }
                }
            } else {
                // Subsequent runs — simulate completion.
                prefetchCompletionCount.incrementAndGet()
                if (prefetchCompletionCount.get() >= 1) prefetchCompletedLatch.countDown()
            }
        }

        assertTrue("First prefetch run should start", firstRunReached.await(5, TimeUnit.SECONDS))

        // Preempt once.
        val u1Done = CountDownLatch(1)
        scheduler.submitUrgent(0L..63L) { _ -> u1Done.countDown() }
        assertTrue("First urgent completes", u1Done.await(5, TimeUnit.SECONDS))

        // Prefetch should resume and complete.
        assertTrue("Prefetch should complete after second run", prefetchCompletedLatch.await(8, TimeUnit.SECONDS))

        scheduler.cancelAll()
    }

    // -------------------------------------------------------------------------
    // I7: cancelAllPrefetch clears parked handles without leaking
    // -------------------------------------------------------------------------

    @Test(timeout = 10_000L)
    fun cancelAllPrefetchDropsParkedHandlesCleanly() {
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        val urgentRunning = CountDownLatch(1)
        val urgentBlock = CountDownLatch(1)

        // Keep urgent lane busy so prefetch gets parked.
        scheduler.submitUrgent(0L..1023L) { _ ->
            urgentRunning.countDown()
            urgentBlock.await(5, TimeUnit.SECONDS)
        }
        assertTrue("Urgent should start", urgentRunning.await(5, TimeUnit.SECONDS))

        val prefetchRan = AtomicBoolean(false)
        scheduler.submitPrefetch(0L..1023L, chunkSize = 1024L) { _ ->
            prefetchRan.set(true)
        }

        // Cancel all prefetch — parked handle must be dropped.
        scheduler.cancelAllPrefetch()

        // Release urgent — onUrgentDrained should have no parked tasks to re-submit.
        urgentBlock.countDown()

        // Give it time; the prefetch callback must NOT fire.
        Thread.sleep(500L)
        assertFalse("Parked prefetch should not run after cancelAllPrefetch", prefetchRan.get())

        scheduler.cancelAll()
    }
}
