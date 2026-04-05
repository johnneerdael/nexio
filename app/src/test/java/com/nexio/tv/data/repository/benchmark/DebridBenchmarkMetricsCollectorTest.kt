package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkMetricsCollectorTest {

    @Test
    fun `finishSustained uses rd probe style consumer p10 for collapse windows`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = buildList {
            repeat(10) { add(800.0) } // warmup
            repeat(20) { add(800.0) }
            repeat(10) { add(5.0) } // transient collapse period
        }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(8, sustained.collectorVersion)
        assertEquals(5_000L, sustained.decisionWindowMs)
        assertEquals(5.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
        assertEquals(800.0, sustained.p50ThroughputMbps ?: 0.0, 0.2)
    }

    @Test
    fun `finishSustained keeps low p10 when throughput is consistently low`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = List(40) { 12.0 }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(8, sustained.collectorVersion)
        assertEquals(5_000L, sustained.decisionWindowMs)
        assertEquals(12.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
    }

    @Test
    fun `finishSustained keeps rd probe consumer p10 resilient to a single zero sample`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = buildList {
            repeat(10) { add(600.0) } // warmup
            addAll(listOf(600.0, 600.0, 0.0, 600.0, 600.0))
            addAll(List(5) { 600.0 })
        }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(5_000L, sustained.decisionWindowMs)
        assertEquals(600.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
    }

    @Test
    fun `finishSustained ignores startup baseline and post completion drain in consumer p10`() {
        val collector = DebridBenchmarkMetricsCollector(
            requiredTransferredBytes = 10L,
            requiredElapsedMs = 3_000L
        )

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordBytesRead(totalBytesRead = 625_000L, sampleAtMs = 1_000L)
        collector.recordBytesRead(totalBytesRead = 125_625_000L, sampleAtMs = 2_000L)
        collector.recordBytesRead(totalBytesRead = 250_625_000L, sampleAtMs = 3_000L)
        collector.recordBytesRead(totalBytesRead = 250_725_000L, sampleAtMs = 4_000L)
        collector.recordBytesRead(totalBytesRead = 250_825_000L, sampleAtMs = 5_000L)

        val sustained = collector.finishSustained()

        assertEquals(1_000.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
    }

    @Test
    fun `finishSustained records cache absorbable deficits separately from draining deficits`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = buildList {
            repeat(10) { add(800.0) }
            addAll(listOf(800.0, 760.0, 740.0, 780.0, 790.0, 760.0, 750.0, 780.0))
        }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(8, sustained.collectorVersion)
        assertTrue((sustained.cacheAbsorbableDeficitCount ?: 0) > 0)
        assertEquals(0, sustained.cacheDrainingDeficitCount ?: 0)
        assertTrue((sustained.estimatedMinCacheHeadroomMs ?: 0L) >= 5_000L)
    }

    private fun feedThroughputWindows(
        collector: DebridBenchmarkMetricsCollector,
        windowsMbps: List<Double>
    ) {
        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        var totalBytes = 0L
        var sampleAtMs = 1_000L
        windowsMbps.forEach { mbps ->
            val bytesThisWindow = (mbps * 1_000_000.0 / 8.0).toLong()
            totalBytes += bytesThisWindow
            collector.recordBytesRead(
                totalBytesRead = totalBytes,
                sampleAtMs = sampleAtMs
            )
            sampleAtMs += 1_000L
        }
    }
}
