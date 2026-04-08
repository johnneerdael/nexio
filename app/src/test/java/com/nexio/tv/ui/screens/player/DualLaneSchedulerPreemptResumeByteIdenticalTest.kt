package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 4 memo invariant I1: byte-identical resume.
 *
 * The prefetch lane accumulates bytes in [PrefetchTaskHandle.scratch] starting at
 * [PrefetchTaskHandle.totalRead]. If preempted mid-chunk, the resumed attempt issues a
 * fresh request from that offset and continues filling the same scratch buffer. The result
 * is byte-for-byte identical to a non-preempted run.
 *
 * This test simulates the accumulation directly on the handle (as SPTM's
 * downloadRangeIntoScratch would do) without real HTTP, using a deterministic
 * FakeDataSource that always returns the same bytes for the same offset.
 */
class DualLaneSchedulerPreemptResumeByteIdenticalTest {

    /**
     * Deterministic fake source: byte at absolute offset N = (N % 251).toByte().
     * Fills [length] bytes into [scratch] starting at [startOffset] in scratch,
     * as if fetching from absolute position [absoluteBase] + [startOffset].
     */
    private fun fillScratch(scratch: ByteArray, startOffset: Int, length: Int, absoluteBase: Long) {
        for (i in 0 until length) {
            val absOffset = absoluteBase + startOffset + i
            scratch[startOffset + i] = (absOffset % 251L).toByte()
        }
    }

    /** Build expected bytes for a range [absoluteBase, absoluteBase+length) from the fake source. */
    private fun expectedBytes(absoluteBase: Long, length: Int): ByteArray =
        ByteArray(length) { i -> ((absoluteBase + i) % 251L).toByte() }

    /**
     * Simulate the full preempt/resume cycle on the scheduler:
     * 1. Submit prefetch — it fills the first half, then waits to be preempted.
     * 2. Submit urgent — preempts the prefetch mid-fill.
     * 3. After urgent drains, onUrgentDrained re-submits the parked handle.
     * 4. The resumed callback fills the second half from handle.totalRead.
     * 5. Assert the full scratch is byte-identical to the expected deterministic sequence.
     */
    @Test(timeout = 15_000L)
    fun preemptAtMidpointProducesByteIdenticalResult() {
        val chunkSize = 128
        val absoluteBase = 0L
        val scheduler = DualLaneScheduler(urgentWorkers = 1, prefetchWorkers = 1)

        val prefetchFirstHalfDone = CountDownLatch(1)
        val prefetchCompleted = CountDownLatch(1)
        val runCount = AtomicInteger(0)
        // Captured reference to the handle so we can read scratch after completion.
        var capturedHandle: PrefetchTaskHandle? = null

        scheduler.submitPrefetch(
            range = absoluteBase until (absoluteBase + chunkSize),
            chunkSize = chunkSize.toLong()
        ) { handle ->
            val run = runCount.incrementAndGet()
            capturedHandle = handle

            if (run == 1) {
                // First run: fill first half, then wait for preemption.
                val firstHalf = chunkSize / 2
                fillScratch(handle.scratch, handle.totalRead, firstHalf, absoluteBase)
                handle.totalRead += firstHalf
                prefetchFirstHalfDone.countDown()

                // Spin until preempted or interrupted.
                val deadline = System.currentTimeMillis() + 8_000L
                while (!handle.preempted.get() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(5L) } catch (_: InterruptedException) { break }
                }
                // Exit — scheduler will re-submit handle after urgent drains.
            } else {
                // Resume run: fill remaining bytes from handle.totalRead.
                val remaining = chunkSize - handle.totalRead
                if (remaining > 0) {
                    fillScratch(handle.scratch, handle.totalRead, remaining, absoluteBase)
                    handle.totalRead += remaining
                }
                prefetchCompleted.countDown()
            }
        }

        assertTrue("First half should be written", prefetchFirstHalfDone.await(5, TimeUnit.SECONDS))

        // Preempt by submitting urgent.
        val urgentDone = CountDownLatch(1)
        scheduler.submitUrgent(absoluteBase until (absoluteBase + chunkSize)) { _ ->
            urgentDone.countDown()
        }
        assertTrue("Urgent should complete", urgentDone.await(5, TimeUnit.SECONDS))

        // After urgent drains, onUrgentDrained re-submits the parked handle.
        // The resumed callback (run == 2) fills the second half and signals prefetchCompleted.
        assertTrue("Prefetch should complete after urgent drains", prefetchCompleted.await(8, TimeUnit.SECONDS))

        val handle = capturedHandle!!
        val expected = expectedBytes(absoluteBase, chunkSize)
        assertArrayEquals(
            "Scratch bytes must be byte-identical to expected deterministic sequence",
            expected,
            handle.scratch
        )

        scheduler.cancelAll()
    }

    /**
     * Direct unit test of the scratch-fill + resume logic without the scheduler:
     * simulate preempt at every byte offset and assert the final scratch is byte-identical
     * to a non-preempted baseline (I1, no scheduler involved — pure arithmetic).
     */
    @Test
    fun scratchFillByteIdenticalAtEveryPreemptOffset() {
        val chunkSize = 256
        val absoluteBase = 1000L
        val expected = expectedBytes(absoluteBase, chunkSize)

        // Baseline: no preemption — fill all at once.
        val baseline = ByteArray(chunkSize)
        fillScratch(baseline, 0, chunkSize, absoluteBase)
        assertArrayEquals("Baseline should match expected", expected, baseline)

        // For each possible preempt offset: fill [0, preemptAt), resume from totalRead.
        for (preemptAt in 1 until chunkSize) {
            val scratch = ByteArray(chunkSize)
            fillScratch(scratch, 0, preemptAt, absoluteBase)
            val totalReadAfterResume = preemptAt
            fillScratch(scratch, totalReadAfterResume, chunkSize - totalReadAfterResume, absoluteBase)
            assertArrayEquals(
                "Scratch preempted at $preemptAt should be byte-identical to baseline",
                expected,
                scratch
            )
        }
    }
}
