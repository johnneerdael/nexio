package com.nexio.tv.instrumentation

import java.util.concurrent.atomic.LongAdder

/**
 * Fixed-bucket histogram backed by [LongAdder]s so producers on the hot path
 * never contend on a CAS. Bucket boundaries are exponential in nanoseconds
 * (1 µs … 1 s) which is the useful range for store/lock/read latencies.
 *
 * Snapshots are read by the writer thread once per 1 s window and emitted as
 * the `store_lock_wait_ms`, `store_write_ms`, `store_read_ms`,
 * `warm_ahead_loop_iteration_ms`, and `cache_write_latency_ms` events.
 */
class HotPathHistogram {

    private val counts: Array<LongAdder> = Array(BUCKETS.size + 1) { LongAdder() }
    private val total: LongAdder = LongAdder()

    fun recordNanos(ns: Long) {
        total.increment()
        val idx = bucketIndex(ns)
        counts[idx].increment()
    }

    /**
     * Atomically reset the histogram and return the snapshot of the cleared
     * window. Writer thread calls this once per second.
     */
    fun snapshot(): HistogramSnapshot {
        val countSamples = LongArray(counts.size)
        var n = 0L
        for (i in counts.indices) {
            val v = counts[i].sumThenReset()
            countSamples[i] = v
            n += v
        }
        total.reset()
        if (n == 0L) return HistogramSnapshot(0L, 0L, 0L)
        val p50Target = (n + 1) / 2
        val p99Target = ((n * 99) + 99) / 100
        var cumulative = 0L
        var p50Ns = 0L
        var p99Ns = 0L
        var p50Set = false
        for (i in countSamples.indices) {
            cumulative += countSamples[i]
            if (!p50Set && cumulative >= p50Target) {
                p50Ns = bucketUpperBound(i)
                p50Set = true
            }
            if (cumulative >= p99Target) {
                p99Ns = bucketUpperBound(i)
                break
            }
        }
        return HistogramSnapshot(p50Ns, p99Ns, n)
    }

    private fun bucketIndex(ns: Long): Int {
        for (i in BUCKETS.indices) {
            if (ns <= BUCKETS[i]) return i
        }
        return BUCKETS.size
    }

    private fun bucketUpperBound(index: Int): Long =
        if (index < BUCKETS.size) BUCKETS[index] else Long.MAX_VALUE

    companion object {
        private val BUCKETS: LongArray = longArrayOf(
            1_000L,            // 1 µs
            3_000L,
            10_000L,           // 10 µs
            30_000L,
            100_000L,          // 100 µs
            300_000L,
            1_000_000L,        // 1 ms
            3_000_000L,
            10_000_000L,       // 10 ms
            30_000_000L,
            100_000_000L,      // 100 ms
            300_000_000L,
            1_000_000_000L     // 1 s
        )
    }
}

data class HistogramSnapshot(val p50Ns: Long, val p99Ns: Long, val count: Long)
