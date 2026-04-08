package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DualLaneSchedulerTest {

    @Test(timeout = 10_000L)
    fun urgentTasksAllComplete() {
        val scheduler = DualLaneScheduler(urgentWorkers = 2, prefetchWorkers = 1)
        val completedUrgent = AtomicInteger(0)
        val urgentLatch = CountDownLatch(3)

        val range = 0L..1023L
        repeat(3) {
            scheduler.submitUrgent(range) { _ ->
                completedUrgent.incrementAndGet()
                urgentLatch.countDown()
            }
        }

        val allUrgentDone = urgentLatch.await(5, TimeUnit.SECONDS)
        assertTrue("All 3 urgent tasks should complete", allUrgentDone)
        assertEquals(3, completedUrgent.get())

        // Submit prefetch when no urgent is pending — it may or may not complete
        val prefetchFuture = scheduler.submitPrefetch(range, chunkSize = 1024L) { _ -> }
        assertNotNull("Prefetch should be accepted when no urgent work is pending", prefetchFuture)

        scheduler.cancelAll()
    }

    @Test(timeout = 10_000L)
    fun cancelAllPrefetchCancelsPrefetchFuturesButNotUrgent() {
        val scheduler = DualLaneScheduler(urgentWorkers = 2, prefetchWorkers = 1)
        val urgentBlockLatch = CountDownLatch(1)
        val urgentRunningLatch = CountDownLatch(1)
        val prefetchRunningLatch = CountDownLatch(1)

        val range = 0L..1023L

        // Submit an urgent task that blocks so pendingUrgentCount stays at 0 after it starts,
        // and we can observe its future independently
        val urgentFuture = scheduler.submitUrgent(range) { _ ->
            urgentRunningLatch.countDown()
            urgentBlockLatch.await()
        }

        // Wait for the urgent task to start running so pendingUrgentTasks decrements after it finishes
        urgentRunningLatch.await(5, TimeUnit.SECONDS)
        // pendingUrgentTasks is still 1 while it is blocked inside the callback

        // Now urgent is running but not yet done — try prefetch (admission control: urgent pending = 1)
        // It should be rejected. Release the urgent latch first so we get a clean state.
        urgentBlockLatch.countDown()
        // Wait for urgent to finish so pendingUrgent goes to 0
        urgentFuture.get(5, TimeUnit.SECONDS)

        // Submit a prefetch that blocks so we can cancel it mid-flight
        val prefetchBlockLatch = CountDownLatch(1)
        val prefetchFuture = scheduler.submitPrefetch(range, chunkSize = 1024L) { _ ->
            prefetchRunningLatch.countDown()
            prefetchBlockLatch.await()
        }
        assertNotNull("Prefetch should be accepted with no pending urgent work", prefetchFuture)

        prefetchRunningLatch.await(5, TimeUnit.SECONDS)

        scheduler.cancelAllPrefetch()

        assertTrue("Prefetch future should be cancelled", prefetchFuture.isCancelled)
        assertTrue("Urgent future should not be cancelled", !urgentFuture.isCancelled)

        scheduler.cancelAll()
    }

    @Test(timeout = 10_000L)
    fun prefetchParkedWhenUrgentPendingThenResumedAfterDrain() {
        val scheduler = DualLaneScheduler(urgentWorkers = 2, prefetchWorkers = 1)
        val blockLatch = CountDownLatch(1)
        val urgentRunningLatch = CountDownLatch(1)

        val range = 0L..1023L

        // Submit urgent task that blocks — keeps pendingUrgentTasks > 0
        scheduler.submitUrgent(range) { _ ->
            urgentRunningLatch.countDown()
            blockLatch.await()
        }

        urgentRunningLatch.await(5, TimeUnit.SECONDS)

        // While urgent is pending, prefetch is now parked (never null) — the old
        // null-return-on-pending-urgent behaviour was removed in Phase 4.
        val parked = scheduler.submitPrefetch(range, chunkSize = 1024L) { _ -> }
        assertNotNull("Prefetch should be accepted (parked) even while urgent work is pending", parked)

        // Release the urgent task
        blockLatch.countDown()

        // Spin-wait for pendingUrgentCount to reach 0 (task finishes asynchronously)
        val deadline = System.currentTimeMillis() + 5_000L
        while (scheduler.pendingUrgentCount > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertEquals(0, scheduler.pendingUrgentCount)

        // Now prefetch should still be accepted (urgent queue is drained)
        val accepted = scheduler.submitPrefetch(range, chunkSize = 1024L) { _ -> }
        assertNotNull("Prefetch should be accepted once urgent queue is drained", accepted)

        scheduler.cancelAll()
    }

    @Test(timeout = 10_000L)
    fun cancelAllShutsDownBothExecutors() {
        val scheduler = DualLaneScheduler(urgentWorkers = 2, prefetchWorkers = 1)

        scheduler.cancelAll()

        assertTrue("Scheduler should be shut down after cancelAll()", scheduler.isShutdown)
    }

    @Test(timeout = 10_000L)
    fun reconfigureCreatesNewExecutors() {
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        // Shut down the original executors via reconfigure
        scheduler.reconfigure(urgentWorkers = 3, prefetchWorkers = 2)

        // After reconfigure, the scheduler should accept new work
        val latch = CountDownLatch(1)
        val range = 0L..1023L
        scheduler.submitUrgent(range) { _ -> latch.countDown() }

        val completed = latch.await(5, TimeUnit.SECONDS)
        assertTrue("Reconfigured scheduler should accept and run new urgent tasks", completed)

        assertTrue("Scheduler should not be shut down after reconfigure", !scheduler.isShutdown)

        scheduler.cancelAll()
    }
}
