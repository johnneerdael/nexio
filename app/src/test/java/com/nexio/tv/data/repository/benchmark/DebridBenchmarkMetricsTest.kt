package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertFalse
import org.junit.Test

class DebridBenchmarkMetricsTest {

    @Test
    fun `benchmark only completes after both 500MB and 120 seconds are satisfied`() {
        val aggregator = DebridBenchmarkAggregator()

        aggregator.recordSample(
            bytesRead = 600.mb,
            elapsedMs = 90.seconds
        )

        assertFalse(aggregator.shouldComplete())
    }

    private val Int.mb: Long
        get() = this * 1024L * 1024L

    private val Int.seconds: Long
        get() = this * 1_000L
}
