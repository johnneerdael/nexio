package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DebridBenchmarkMetricsCollectorTest {

    @Test
    fun `finishSustained ignores transient collapse windows for decision percentile`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = buildList {
            repeat(10) { add(800.0) } // warmup
            repeat(20) { add(800.0) }
            repeat(10) { add(5.0) } // transient collapse period
        }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(5, sustained.collectorVersion)
        assertEquals(5_000L, sustained.decisionWindowMs)
        assertTrue((sustained.p10ThroughputMbps ?: 0.0) > 700.0)
    }

    @Test
    fun `finishSustained keeps low p10 when throughput is consistently low`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = List(40) { 12.0 }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(5, sustained.collectorVersion)
        assertEquals(5_000L, sustained.decisionWindowMs)
        assertEquals(12.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
    }

    @Test
    fun `finishSustained uses 5 second consumer decision windows so single second pauses do not crush p10`() {
        val collector = DebridBenchmarkMetricsCollector()
        val windowsMbps = buildList {
            repeat(10) { add(600.0) } // warmup
            addAll(listOf(600.0, 600.0, 0.0, 600.0, 600.0))
            addAll(List(5) { 600.0 })
        }

        feedThroughputWindows(collector, windowsMbps)
        val sustained = collector.finishSustained()

        assertEquals(5_000L, sustained.decisionWindowMs)
        assertEquals(480.0, sustained.p10ThroughputMbps ?: 0.0, 0.2)
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
