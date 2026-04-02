package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkMetricsTest {

    @Test
    fun `collector only completes after both 500MB and 120 seconds are satisfied`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 5.seconds
        )
        collector.recordBytesRead(
            totalBytesRead = 600.mb,
            sampleAtMs = 90.seconds
        )

        assertFalse(collector.shouldComplete())
    }

    @Test
    fun `live summary uses request start to aggregate startup elapsed and throughput`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 1.seconds,
            firstByteAtMs = 21.seconds
        )
        collector.recordBytesRead(
            totalBytesRead = 100.mb,
            sampleAtMs = 61.seconds
        )
        val summary = collector.currentSummary()

        assertEquals(20.seconds, summary.startupTimeMs)
        assertEquals(100.mb, summary.transferredBytes)
        assertEquals(60.seconds, summary.elapsedMs)
        assertEquals(20.97152, summary.sustainedThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `collector reports p10 stddev and coefficient of variation from throughput windows`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordBytesRead(totalBytesRead = 15_000_000L, sampleAtMs = 1.seconds)
        collector.recordBytesRead(totalBytesRead = 25_000_000L, sampleAtMs = 2.seconds)
        collector.recordBytesRead(totalBytesRead = 37_500_000L, sampleAtMs = 3.seconds)

        val sustained = collector.finishSustained()

        assertEquals(100.0, sustained.averageThroughputMbps ?: 0.0, 0.00001)
        assertEquals(80.0, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(100.0, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(120.0, sustained.peakThroughputMbps ?: 0.0, 0.00001)
        assertTrue((sustained.throughputStddevMbps ?: 0.0) > 0.0)
        assertTrue((sustained.throughputCv ?: 0.0) > 0.0)
    }

    @Test
    fun `collector reports seek p50 p95 and p99 from seek samples`() {
        val collector = DebridBenchmarkMetricsCollector()

        listOf(120L, 150L, 190L, 400L, 600L).forEachIndexed { index, ttfb ->
            collector.recordSeekSample(
                DebridBenchmarkSeekSample(
                    targetOffsetBytes = index * 1_000_000L,
                    ttfbMs = ttfb,
                    succeeded = true
                )
            )
        }

        val seek = collector.finishSeek()

        assertEquals(190L, seek.seekTtfbP50Ms)
        assertEquals(600L, seek.seekTtfbP95Ms)
        assertEquals(600L, seek.seekTtfbP99Ms)
    }

    @Test
    fun `collector completes once both thresholds are met`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 3.seconds
        )
        collector.recordBytesRead(
            totalBytesRead = 510.mb,
            sampleAtMs = 121.seconds
        )

        assertTrue(collector.shouldComplete())
    }

    private val Int.mb: Long
        get() = this * 1024L * 1024L

    private val Int.seconds: Long
        get() = this * 1_000L
}
