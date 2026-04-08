package com.nexio.tv.instrumentation

import org.jctools.queues.MpscArrayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Sanity stress on the jctools MpscArrayQueue<TraceRecord> usage shape.
 * 4 producers + 1 consumer; asserts no records are silently lost — every
 * record either reaches the consumer or is accounted for via the
 * drop-on-overflow counter.
 */
class MpscRingStressTest {

    /**
     * 4 producers push a fixed small batch into a ring sized well above the
     * total, so no overflow is expected. The consumer drains after all producers
     * finish. Asserts every produced record reaches the consumer and the
     * obtain/recycle pool stays thread-safe.
     */
    @Test
    fun fourProducersOneConsumerNoOverflow() {
        val producers = 4
        val perProducer = 2_000
        val totalExpected = (producers * perProducer).toLong()
        // Capacity well above total so ring.offer cannot fail.
        val capacity = 16_384
        val ring = MpscArrayQueue<TraceRecord>(capacity)

        val overflow = AtomicLong(0)
        val consumed = AtomicLong(0)

        val start = CountDownLatch(1)
        val producerDone = CountDownLatch(producers)

        val producerThreads = (0 until producers).map {
            Thread {
                start.await()
                repeat(perProducer) {
                    val rec = TraceRecord.obtain(EventFamily.RANGE, "x")
                    if (!ring.offer(rec)) {
                        overflow.incrementAndGet()
                        rec.recycle()
                    }
                }
                producerDone.countDown()
            }.also { it.start() }
        }

        start.countDown()
        val producersFinished = producerDone.await(10, TimeUnit.SECONDS)
        assertTrue("producers did not finish within 10 s", producersFinished)
        producerThreads.forEach { it.join(1_000) }

        // Drain strictly after producers finish — deterministic, no race.
        var rec = ring.poll()
        while (rec != null) {
            consumed.incrementAndGet()
            rec.recycle()
            rec = ring.poll()
        }

        assertEquals("no records should be dropped to overflow", 0L, overflow.get())
        assertEquals(
            "every produced record must reach the consumer",
            totalExpected,
            consumed.get()
        )
    }
}
