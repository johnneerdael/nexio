package com.nexio.tv.data.repository.benchmark

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

class DebridBenchmarkMetricsCollector(
    private val requiredTransferredBytes: Long = 500L * 1024L * 1024L,
    private val requiredElapsedMs: Long = 120_000L,
    private val stallThresholdMs: Long = 2_000L
) {
    private var requestStartedAtMs: Long? = null
    private var firstByteAtMs: Long? = null
    private var totalBytesRead = 0L
    private var lastSampleAtMs: Long? = null
    private var lastSampleBytesRead = 0L
    private var stallCount = 0
    private var maxReadGapMs = 0L
    private val throughputWindowsMbps = mutableListOf<Double>()
    private val seekSamples = mutableListOf<DebridBenchmarkSeekSample>()

    fun recordStartup(
        requestStartedAtMs: Long,
        firstByteAtMs: Long
    ) {
        this.requestStartedAtMs = requestStartedAtMs.coerceAtLeast(0L)
        if (this.firstByteAtMs == null) {
            this.firstByteAtMs = firstByteAtMs.coerceAtLeast(this.requestStartedAtMs ?: 0L)
        }
    }

    fun recordBytesRead(
        totalBytesRead: Long,
        sampleAtMs: Long
    ) {
        val sampleTimeMs = sampleAtMs.coerceAtLeast(0L)
        val clampedBytesRead = totalBytesRead.coerceAtLeast(0L)
        val previousSampleAtMs = lastSampleAtMs

        if (previousSampleAtMs != null) {
            val deltaMs = (sampleTimeMs - previousSampleAtMs).coerceAtLeast(0L)
            val deltaBytes = (clampedBytesRead - lastSampleBytesRead).coerceAtLeast(0L)
            if (deltaMs > 0L) {
                throughputWindowsMbps += deltaBytes.toMbps(deltaMs)
            }
        } else {
            val windowStartMs = firstByteAtMs ?: requestStartedAtMs
            val deltaMs = windowStartMs?.let { sampleTimeMs - it }?.coerceAtLeast(0L) ?: 0L
            if (deltaMs > 0L) {
                throughputWindowsMbps += clampedBytesRead.toMbps(deltaMs)
            }
        }

        this.totalBytesRead = clampedBytesRead
        lastSampleAtMs = sampleTimeMs
        lastSampleBytesRead = clampedBytesRead
    }

    fun recordReadGap(gapMs: Long) {
        val clampedGapMs = gapMs.coerceAtLeast(0L)
        maxReadGapMs = maxOf(maxReadGapMs, clampedGapMs)
        if (clampedGapMs >= stallThresholdMs) {
            stallCount += 1
        }
    }

    fun recordSeekSample(sample: DebridBenchmarkSeekSample) {
        seekSamples += sample
    }

    fun shouldComplete(): Boolean {
        return totalBytesRead >= requiredTransferredBytes &&
            currentSummary().elapsedMs >= requiredElapsedMs
    }

    fun currentSummary(): DebridBenchmarkSummary {
        val requestStarted = requestStartedAtMs ?: 0L
        val elapsedMs = lastSampleAtMs?.let { (it - requestStarted).coerceAtLeast(0L) } ?: 0L
        val startupTimeMs = firstByteAtMs?.let { (it - requestStarted).coerceAtLeast(0L) }
        val streamingDurationMs = startupTimeMs?.let { elapsedMs - it }?.takeIf { it > 0L }
        return DebridBenchmarkSummary(
            startupTimeMs = startupTimeMs,
            sustainedThroughputMbps = streamingDurationMs?.let { totalBytesRead.toMbps(it) },
            transferredBytes = totalBytesRead,
            elapsedMs = elapsedMs
        )
    }

    fun finishStartup(): DebridBenchmarkStartupMetrics {
        return DebridBenchmarkStartupMetrics(
            initialTtfbMs = currentSummary().startupTimeMs,
            startupFailureRate = if (firstByteAtMs != null) 0.0 else 1.0
        )
    }

    fun finishSustained(): DebridBenchmarkSustainedMetrics {
        val sortedWindows = throughputWindowsMbps.sorted()
        val average = sortedWindows.takeIf { it.isNotEmpty() }?.average()
        val stddev = sortedWindows.standardDeviation(average)
        return DebridBenchmarkSustainedMetrics(
            averageThroughputMbps = average,
            p10ThroughputMbps = sortedWindows.percentileNearestRank(0.10),
            p50ThroughputMbps = sortedWindows.percentileNearestRank(0.50),
            peakThroughputMbps = sortedWindows.maxOrNull(),
            throughputStddevMbps = stddev,
            throughputCv = if (average != null && average > 0.0 && stddev != null) {
                stddev / average
            } else if (average == 0.0 && stddev == 0.0) {
                0.0
            } else {
                null
            },
            stallCount = stallCount,
            maxReadGapMs = maxReadGapMs,
            bytesTransferred = totalBytesRead,
            elapsedMs = currentSummary().elapsedMs
        )
    }

    fun finishSeek(): DebridBenchmarkSeekMetrics {
        val successfulSamples = seekSamples
            .filter { it.succeeded && it.ttfbMs != null }
            .map { requireNotNull(it.ttfbMs).toDouble() }
            .sorted()
        val totalSamples = seekSamples.size
        val stddev = successfulSamples.standardDeviation()
        return DebridBenchmarkSeekMetrics(
            seekTtfbP50Ms = successfulSamples.percentileNearestRank(0.50)?.toLong(),
            seekTtfbP95Ms = successfulSamples.percentileNearestRank(0.95)?.toLong(),
            seekTtfbP99Ms = successfulSamples.percentileNearestRank(0.99)?.toLong(),
            seekTtfbStddevMs = stddev,
            seekFailRate = if (totalSamples == 0) null else {
                (totalSamples - successfulSamples.size).toDouble() / totalSamples.toDouble()
            }
        )
    }

    fun rawSamples(): DebridBenchmarkRawSamples {
        return DebridBenchmarkRawSamples(
            throughputWindowsMbps = throughputWindowsMbps.toList(),
            seekSamples = seekSamples.toList()
        )
    }

    private fun Long.toMbps(durationMs: Long): Double {
        return toDouble() * 8.0 / durationMs.toDouble() / 1_000.0
    }

    private fun List<Double>.percentileNearestRank(percentile: Double): Double? {
        if (isEmpty()) return null
        val rank = ceil(percentile.coerceIn(0.0, 1.0) * size.toDouble()).toInt().coerceAtLeast(1)
        return this[rank - 1]
    }

    private fun List<Double>.standardDeviation(average: Double? = takeIf { isNotEmpty() }?.average()): Double? {
        if (isEmpty() || average == null) return null
        val variance = sumOf { (it - average).pow(2) } / size.toDouble()
        return sqrt(variance)
    }
}
