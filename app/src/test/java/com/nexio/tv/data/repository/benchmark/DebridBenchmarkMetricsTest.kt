package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkMetricsTest {

    @Test
    fun `benchmark only completes after both 500MB and 120 seconds are satisfied`() {
        val aggregator = DebridBenchmarkAggregator()

        aggregator.recordTransferSample(
            bytesRead = 600.mb,
            requestStartedAtMs = 0L,
            firstByteAtMs = 5.seconds,
            sampleAtMs = 90.seconds
        )

        assertFalse(aggregator.shouldComplete())
    }

    @Test
    fun `summary uses request start to aggregate startup elapsed and throughput`() {
        val aggregator = DebridBenchmarkAggregator()

        val summary = aggregator.recordTransferSample(
            bytesRead = 100.mb,
            requestStartedAtMs = 1.seconds,
            firstByteAtMs = 21.seconds,
            sampleAtMs = 61.seconds
        )

        assertEquals(20.seconds, summary.startupTimeMs)
        assertEquals(100.mb, summary.transferredBytes)
        assertEquals(60.seconds, summary.elapsedMs)
        assertEquals(20.97152, summary.sustainedThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `benchmark completes once both thresholds are met`() {
        val aggregator = DebridBenchmarkAggregator()

        aggregator.recordTransferSample(
            bytesRead = 510.mb,
            requestStartedAtMs = 0L,
            firstByteAtMs = 3.seconds,
            sampleAtMs = 121.seconds
        )

        assertTrue(aggregator.shouldComplete())
    }

    private val Int.mb: Long
        get() = this * 1024L * 1024L

    private val Int.seconds: Long
        get() = this * 1_000L
}
