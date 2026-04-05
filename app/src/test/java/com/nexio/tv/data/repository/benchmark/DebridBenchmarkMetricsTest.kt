package com.nexio.tv.data.repository.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun `collector aggregates sub-second reads into fixed throughput windows`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordBytesRead(totalBytesRead = 4.mb, sampleAtMs = 250L)
        collector.recordBytesRead(totalBytesRead = 8.mb, sampleAtMs = 500L)
        collector.recordBytesRead(totalBytesRead = 12.mb, sampleAtMs = 750L)
        collector.recordBytesRead(totalBytesRead = 16.mb, sampleAtMs = 1.seconds)

        val sustained = collector.finishSustained()
        val rawSamples = collector.rawSamples()

        assertEquals(listOf(134.217728), rawSamples.throughputWindowsMbps)
        assertEquals(16.mb, rawSamples.throughputBuckets.single().bytesTransferred)
        assertEquals(134.217728, sustained.averageThroughputMbps ?: 0.0, 0.00001)
        assertEquals(134.217728, sustained.derivedAverageThroughputMbps ?: 0.0, 0.00001)
        assertTrue(sustained.actionable)
        assertEquals(134.217728, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(134.217728, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(134.217728, sustained.peakThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `collector keeps sustained autoplay metrics on outer read samples even when transport samples exist`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordBytesRead(totalBytesRead = 64.mb, sampleAtMs = 1.seconds)
        collector.recordBytesRead(totalBytesRead = 128.mb, sampleAtMs = 2.seconds)

        collector.recordTransportBytesRead(bytesRead = 4.mb, sampleAtMs = 1.seconds)
        collector.recordTransportBytesRead(bytesRead = 8.mb, sampleAtMs = 2.seconds)
        collector.recordTransportBytesRead(bytesRead = 12.mb, sampleAtMs = 3.seconds)

        val sustained = collector.finishSustained()
        val rawSamples = collector.rawSamples()

        assertEquals(listOf(536.870912, 536.870912), rawSamples.throughputWindowsMbps)
        assertEquals(128.mb, sustained.bytesTransferred ?: 0L)
        assertEquals(2.seconds, sustained.elapsedMs ?: 0L)
        assertEquals(536.870912, sustained.averageThroughputMbps ?: 0.0, 0.00001)
        assertEquals(536.870912, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(536.870912, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(536.870912, sustained.peakThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `collector derives conservative floor from steady state outer buckets after warm up`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )

        repeat(10) { index ->
            collector.recordBytesRead(
                totalBytesRead = (index + 1) * 50_000_000L,
                sampleAtMs = (index + 1).seconds
            )
        }
        repeat(10) { index ->
            collector.recordBytesRead(
                totalBytesRead = 500_000_000L + ((index + 1) * 100_000_000L),
                sampleAtMs = (index + 11).seconds
            )
        }

        val sustained = collector.finishSustained()

        assertEquals(800.0, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(800.0, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(800.0, sustained.peakThroughputMbps ?: 0.0, 0.00001)
        assertEquals(0.0, sustained.throughputCv ?: 0.0, 0.00001)
    }

    @Test
    fun `bursty transport prefetch does not collapse consumer side sustained floor`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )

        repeat(20) { index ->
            collector.recordBytesRead(
                totalBytesRead = ((index + 1) * 100_000_000L),
                sampleAtMs = (index + 1).seconds
            )
        }

        collector.recordTransportBytesRead(bytesRead = 400_000_000L, sampleAtMs = 1.seconds)
        collector.recordTransportBytesRead(bytesRead = 400_000_000L, sampleAtMs = 2.seconds)
        collector.recordTransportBytesRead(bytesRead = 400_000_000L, sampleAtMs = 11.seconds)
        collector.recordTransportBytesRead(bytesRead = 400_000_000L, sampleAtMs = 12.seconds)

        val sustained = collector.finishSustained()

        assertEquals(800.0, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(800.0, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(800.0, sustained.peakThroughputMbps ?: 0.0, 0.00001)
        assertTrue(sustained.actionable)
    }

    @Test
    fun `collector uses smoothed 5 second decision windows for autoplay floor`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )

        repeat(10) { index ->
            collector.recordBytesRead(
                totalBytesRead = (index + 1) * 100_000_000L,
                sampleAtMs = (index + 1).seconds
            )
        }
        collector.recordBytesRead(totalBytesRead = 1_000_400_000L, sampleAtMs = 11.seconds)
        collector.recordBytesRead(totalBytesRead = 1_000_800_000L, sampleAtMs = 12.seconds)
        collector.recordBytesRead(totalBytesRead = 1_100_800_000L, sampleAtMs = 13.seconds)
        collector.recordBytesRead(totalBytesRead = 1_200_800_000L, sampleAtMs = 14.seconds)
        collector.recordBytesRead(totalBytesRead = 1_300_800_000L, sampleAtMs = 15.seconds)

        val sustained = collector.finishSustained()

        assertEquals(693.76, sustained.averageThroughputMbps ?: 0.0, 0.00001)
        assertEquals(481.28, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(800.0, sustained.peakThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `collector preserves bytes across multiple reads in the same millisecond`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordBytesRead(totalBytesRead = 4.mb, sampleAtMs = 1.seconds)
        collector.recordBytesRead(totalBytesRead = 8.mb, sampleAtMs = 1.seconds)
        collector.recordBytesRead(totalBytesRead = 12.mb, sampleAtMs = 1.seconds)
        collector.recordBytesRead(totalBytesRead = 16.mb, sampleAtMs = 2.seconds)

        val sustained = collector.finishSustained()
        val rawSamples = collector.rawSamples()

        assertEquals(listOf(33.554432, 100.663296), rawSamples.throughputWindowsMbps)
        assertEquals(16.mb, sustained.bytesTransferred ?: 0L)
        assertEquals(2.seconds, sustained.elapsedMs ?: 0L)
        assertEquals(67.108864, sustained.averageThroughputMbps ?: 0.0, 0.00001)
        assertEquals(33.554432, sustained.p10ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(33.554432, sustained.p50ThroughputMbps ?: 0.0, 0.00001)
        assertEquals(100.663296, sustained.peakThroughputMbps ?: 0.0, 0.00001)
    }

    @Test
    fun `transport collector is diagnostic only when no outer read samples exist`() {
        val collector = DebridBenchmarkMetricsCollector()

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )
        collector.recordTransportBytesRead(bytesRead = 4.mb, sampleAtMs = 1.seconds)
        collector.recordTransportBytesRead(bytesRead = 8.mb, sampleAtMs = 1.seconds)
        collector.recordTransportBytesRead(bytesRead = 12.mb, sampleAtMs = 1.seconds)
        collector.recordTransportBytesRead(bytesRead = 16.mb, sampleAtMs = 2.seconds)

        val sustained = collector.finishSustained()
        val rawSamples = collector.rawSamples()

        assertEquals(emptyList<Double>(), rawSamples.throughputWindowsMbps)
        assertEquals(0L, sustained.bytesTransferred ?: 0L)
        assertEquals(0L, sustained.elapsedMs ?: 0L)
        assertEquals(null, sustained.averageThroughputMbps)
        assertEquals(null, sustained.p10ThroughputMbps)
        assertEquals(null, sustained.p50ThroughputMbps)
        assertEquals(null, sustained.peakThroughputMbps)
    }

    @Test
    fun `parallel transport diagnostics do not create autoplay throughput metrics without outer reads`() {
        val collector = DebridBenchmarkMetricsCollector()
        val executor = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(4)

        collector.recordStartup(
            requestStartedAtMs = 0L,
            firstByteAtMs = 0L
        )

        repeat(4) {
            executor.execute {
                ready.countDown()
                start.await(5, TimeUnit.SECONDS)
                repeat(1_000) {
                    collector.recordTransportBytesRead(
                        bytesRead = 1.mb,
                        sampleAtMs = 1.seconds
                    )
                }
                done.countDown()
            }
        }

        assertTrue(ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        collector.recordTransportBytesRead(
            bytesRead = 4.mb,
            sampleAtMs = 2.seconds
        )

        val sustained = collector.finishSustained()

        assertEquals(0L, sustained.bytesTransferred ?: 0L)
        assertEquals(0L, sustained.elapsedMs ?: 0L)
        assertEquals(false, sustained.actionable)
        assertEquals(null, sustained.averageThroughputMbps)
        assertEquals(null, sustained.derivedAverageThroughputMbps)
        assertEquals(null, sustained.p10ThroughputMbps)
    }

    @Test
    fun `safe sustained budget is disabled when sustained profile is marked non actionable`() {
        val profile = DebridBenchmarkTransportProfile(
            startup = DebridBenchmarkStartupMetrics(
                initialTtfbMs = 100L,
                startupFailureRate = 0.0
            ),
            sustained = DebridBenchmarkSustainedMetrics(
                averageThroughputMbps = 64.0,
                derivedAverageThroughputMbps = 540.0,
                actionable = false,
                p10ThroughputMbps = 60.0,
                p50ThroughputMbps = 64.0,
                peakThroughputMbps = 66.0,
                throughputStddevMbps = 1.0,
                throughputCv = 0.01,
                stallCount = 0,
                maxReadGapMs = 100L,
                bytesTransferred = 8_000_000_000L,
                elapsedMs = 120_000L
            ),
            seek = DebridBenchmarkSeekMetrics(),
            rawSamples = DebridBenchmarkRawSamples()
        )

        assertEquals(null, profile.safeSustainedBudgetMbps())
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
