package com.nexio.tv.data.repository.benchmark

import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sqrt

class DebridBenchmarkMetricsCollector(
    private val requiredTransferredBytes: Long = 500L * 1024L * 1024L,
    private val requiredElapsedMs: Long = 120_000L,
    private val stallThresholdMs: Long = 2_000L,
    private val throughputWindowMs: Long = 1_000L
) {
    companion object {
        private const val COLLECTOR_VERSION = 2
        private const val SAMPLING_MODE_FIXED_TIME_BUCKET = "fixed_time_bucket"
        private const val CONSISTENCY_TOLERANCE_RATIO = 0.10
        private const val STEADY_STATE_WARMUP_MS = 10_000L
    }

    private var requestStartedAtMs: Long? = null
    private var firstByteAtMs: Long? = null
    private var totalBytesRead = 0L
    private var lastSampleAtMs: Long? = null
    private var lastSampleBytesRead = 0L
    private var pendingOuterDeltaBytes = 0.0
    private var stallCount = 0
    private var maxReadGapMs = 0L
    private val throughputBuckets = mutableListOf<DebridBenchmarkThroughputBucketSample>()
    private var throughputStreamStartAtMs: Long? = null
    private var throughputWindowIndex = 0L
    private var throughputWindowAccumulatedBytes = 0.0
    private var throughputWindowAccumulatedMs = 0L
    private var transportLastSampleAtMs: Long? = null
    private var pendingTransportBytes = 0.0
    private var transportStallCount = 0
    private var transportMaxReadGapMs = 0L
    private val transportThroughputBuckets = mutableListOf<DebridBenchmarkThroughputBucketSample>()
    private var transportThroughputStreamStartAtMs: Long? = null
    private var transportThroughputWindowIndex = 0L
    private var transportThroughputWindowAccumulatedBytes = 0.0
    private var transportThroughputWindowAccumulatedMs = 0L
    private val seekSamples = mutableListOf<DebridBenchmarkSeekSample>()

    fun recordStartup(
        requestStartedAtMs: Long,
        firstByteAtMs: Long
    ) {
        this.requestStartedAtMs = requestStartedAtMs.coerceAtLeast(0L)
        if (this.firstByteAtMs == null) {
            this.firstByteAtMs = firstByteAtMs.coerceAtLeast(this.requestStartedAtMs ?: 0L)
            throughputStreamStartAtMs = this.firstByteAtMs
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
            pendingOuterDeltaBytes += deltaBytes.toDouble()
            if (deltaMs > 0L) {
                recordThroughputInterval(
                    intervalStartMs = previousSampleAtMs,
                    intervalEndMs = sampleTimeMs,
                    intervalBytes = pendingOuterDeltaBytes
                )
                pendingOuterDeltaBytes = 0.0
            }
        } else {
            val windowStartMs = firstByteAtMs ?: requestStartedAtMs
            val deltaMs = windowStartMs?.let { sampleTimeMs - it }?.coerceAtLeast(0L) ?: 0L
            pendingOuterDeltaBytes += clampedBytesRead.toDouble()
            if (deltaMs > 0L) {
                recordThroughputInterval(
                    intervalStartMs = requireNotNull(windowStartMs),
                    intervalEndMs = sampleTimeMs,
                    intervalBytes = pendingOuterDeltaBytes
                )
                pendingOuterDeltaBytes = 0.0
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

    fun recordTransportBytesRead(
        bytesRead: Long,
        sampleAtMs: Long
    ) {
        val clampedBytesRead = bytesRead.coerceAtLeast(0L)
        if (clampedBytesRead <= 0L) return

        val sampleTimeMs = sampleAtMs.coerceAtLeast(0L)
        val previousSampleAtMs = transportLastSampleAtMs
        if (previousSampleAtMs != null) {
            val gapMs = (sampleTimeMs - previousSampleAtMs).coerceAtLeast(0L)
            transportMaxReadGapMs = maxOf(transportMaxReadGapMs, gapMs)
            if (gapMs >= stallThresholdMs) {
                transportStallCount += 1
            }
        }

        pendingTransportBytes += clampedBytesRead.toDouble()
        if (previousSampleAtMs != null && sampleTimeMs > previousSampleAtMs) {
            recordTransportThroughputInterval(
                intervalStartMs = previousSampleAtMs,
                intervalEndMs = sampleTimeMs,
                intervalBytes = pendingTransportBytes
            )
            pendingTransportBytes = 0.0
        }
        transportLastSampleAtMs = sampleTimeMs
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
        val completedBuckets = completedSustainedBuckets()
        val steadyStateBuckets = steadyStateCompletedSustainedBuckets(completedBuckets)
        val sortedWindows = completedBuckets.map { it.throughputMbps }.sorted()
        val steadyStateWindows = steadyStateBuckets.map { it.throughputMbps }.sorted()
        val average = sortedWindows.takeIf { it.isNotEmpty() }?.average()
        val steadyStateAverage = steadyStateWindows.takeIf { it.isNotEmpty() }?.average()
        val completedBytesTransferred = completedBuckets.sumOf { it.bytesTransferred }
        val completedElapsedMs = completedBuckets.sumOf { it.durationMs }
        val derivedAverage = completedBytesTransferred
            .takeIf { completedElapsedMs > 0L }
            ?.toMbps(completedElapsedMs)
        val stddev = steadyStateWindows.standardDeviation(steadyStateAverage)
        val actionable = average != null &&
            derivedAverage != null &&
            average.isWithinRatio(
                other = derivedAverage,
                toleranceRatio = CONSISTENCY_TOLERANCE_RATIO
            )
        return DebridBenchmarkSustainedMetrics(
            collectorVersion = COLLECTOR_VERSION,
            samplingMode = SAMPLING_MODE_FIXED_TIME_BUCKET,
            bucketMs = throughputWindowMs,
            averageThroughputMbps = average,
            derivedAverageThroughputMbps = derivedAverage,
            actionable = actionable,
            p10ThroughputMbps = steadyStateWindows.percentileNearestRank(0.10),
            p50ThroughputMbps = steadyStateWindows.percentileNearestRank(0.50),
            peakThroughputMbps = steadyStateWindows.maxOrNull(),
            throughputStddevMbps = stddev,
            throughputCv = if (steadyStateAverage != null && steadyStateAverage > 0.0 && stddev != null) {
                stddev / steadyStateAverage
            } else if (steadyStateAverage == 0.0 && stddev == 0.0) {
                0.0
            } else {
                null
            },
            stallCount = activeStallCount(),
            maxReadGapMs = activeMaxReadGapMs(),
            bytesTransferred = completedBytesTransferred,
            elapsedMs = completedElapsedMs
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
        val effectiveBuckets = effectiveSustainedBuckets()
        return DebridBenchmarkRawSamples(
            throughputWindowsMbps = effectiveBuckets.map { it.throughputMbps },
            throughputBuckets = effectiveBuckets,
            seekSamples = seekSamples.toList()
        )
    }

    private fun recordThroughputInterval(
        intervalStartMs: Long,
        intervalEndMs: Long,
        intervalBytes: Double
    ) {
        if (intervalEndMs <= intervalStartMs || intervalBytes <= 0.0) return

        var cursorMs = intervalStartMs
        var remainingBytes = intervalBytes
        val totalDurationMs = (intervalEndMs - intervalStartMs).toDouble()

        while (cursorMs < intervalEndMs) {
            ensureThroughputWindowStarted()
            val currentWindowStart = requireNotNull(currentWindowStartAtMs())
            val currentWindowEnd = currentWindowStart + throughputWindowMs
            val segmentEndMs = minOf(intervalEndMs, currentWindowEnd)
            val segmentDurationMs = segmentEndMs - cursorMs
            if (segmentDurationMs <= 0L) {
                cursorMs = segmentEndMs
                continue
            }
            val segmentBytes = if (segmentEndMs == intervalEndMs) {
                remainingBytes
            } else {
                intervalBytes * (segmentDurationMs.toDouble() / totalDurationMs)
            }
            throughputWindowAccumulatedBytes += segmentBytes
            throughputWindowAccumulatedMs += segmentDurationMs
            remainingBytes -= segmentBytes
            cursorMs = segmentEndMs

            if (throughputWindowAccumulatedMs >= throughputWindowMs) {
                flushCurrentThroughputWindow(complete = true)
            }
        }
    }

    private fun recordTransportThroughputInterval(
        intervalStartMs: Long,
        intervalEndMs: Long,
        intervalBytes: Double
    ) {
        if (intervalEndMs <= intervalStartMs || intervalBytes <= 0.0) return

        var cursorMs = intervalStartMs
        var remainingBytes = intervalBytes
        val totalDurationMs = (intervalEndMs - intervalStartMs).toDouble()

        while (cursorMs < intervalEndMs) {
            ensureTransportThroughputWindowStarted(cursorMs)
            val currentWindowStart = requireNotNull(currentTransportWindowStartAtMs())
            val currentWindowEnd = currentWindowStart + throughputWindowMs
            val segmentEndMs = minOf(intervalEndMs, currentWindowEnd)
            val segmentDurationMs = segmentEndMs - cursorMs
            if (segmentDurationMs <= 0L) {
                cursorMs = segmentEndMs
                continue
            }
            val segmentBytes = if (segmentEndMs == intervalEndMs) {
                remainingBytes
            } else {
                intervalBytes * (segmentDurationMs.toDouble() / totalDurationMs)
            }
            transportThroughputWindowAccumulatedBytes += segmentBytes
            transportThroughputWindowAccumulatedMs += segmentDurationMs
            remainingBytes -= segmentBytes
            cursorMs = segmentEndMs

            if (transportThroughputWindowAccumulatedMs >= throughputWindowMs) {
                flushCurrentTransportThroughputWindow(complete = true)
            }
        }
    }

    private fun ensureThroughputWindowStarted() {
        if (throughputStreamStartAtMs == null) {
            throughputStreamStartAtMs = firstByteAtMs ?: requestStartedAtMs
        }
    }

    private fun ensureTransportThroughputWindowStarted(sampleAtMs: Long) {
        if (transportThroughputStreamStartAtMs == null) {
            // Transport-side sampling starts when the parallel data source actually begins
            // delivering bytes, which can lag behind benchmark first-byte timing.
            transportThroughputStreamStartAtMs = sampleAtMs
        }
    }

    private fun currentWindowStartAtMs(): Long? {
        val streamStartAtMs = throughputStreamStartAtMs ?: return null
        return streamStartAtMs + (throughputWindowIndex * throughputWindowMs)
    }

    private fun currentTransportWindowStartAtMs(): Long? {
        val streamStartAtMs = transportThroughputStreamStartAtMs ?: return null
        return streamStartAtMs + (transportThroughputWindowIndex * throughputWindowMs)
    }

    private fun flushCurrentThroughputWindow(complete: Boolean) {
        val durationMs = throughputWindowAccumulatedMs
        if (durationMs > 0L) {
            throughputBuckets += DebridBenchmarkThroughputBucketSample(
                startOffsetMs = throughputWindowIndex * throughputWindowMs,
                durationMs = durationMs,
                bytesTransferred = throughputWindowAccumulatedBytes.toLong(),
                throughputMbps = throughputWindowAccumulatedBytes.toMbps(durationMs),
                complete = complete
            )
        }
        throughputWindowIndex += 1L
        throughputWindowAccumulatedBytes = 0.0
        throughputWindowAccumulatedMs = 0L
    }

    private fun flushCurrentTransportThroughputWindow(complete: Boolean) {
        val durationMs = transportThroughputWindowAccumulatedMs
        if (durationMs > 0L) {
            transportThroughputBuckets += DebridBenchmarkThroughputBucketSample(
                startOffsetMs = transportThroughputWindowIndex * throughputWindowMs,
                durationMs = durationMs,
                bytesTransferred = transportThroughputWindowAccumulatedBytes.toLong(),
                throughputMbps = transportThroughputWindowAccumulatedBytes.toMbps(durationMs),
                complete = complete
            )
        }
        transportThroughputWindowIndex += 1L
        transportThroughputWindowAccumulatedBytes = 0.0
        transportThroughputWindowAccumulatedMs = 0L
    }

    private fun completedThroughputBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return throughputBuckets.filter { it.complete }
    }

    private fun completedTransportThroughputBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return transportThroughputBuckets.filter { it.complete }
    }

    private fun effectiveThroughputBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        if (throughputWindowAccumulatedMs <= 0L || throughputWindowAccumulatedBytes <= 0.0) {
            return throughputBuckets.toList()
        }
        return buildList {
            addAll(throughputBuckets)
            add(
                DebridBenchmarkThroughputBucketSample(
                    startOffsetMs = throughputWindowIndex * throughputWindowMs,
                    durationMs = throughputWindowAccumulatedMs,
                    bytesTransferred = throughputWindowAccumulatedBytes.toLong(),
                    throughputMbps = throughputWindowAccumulatedBytes.toMbps(throughputWindowAccumulatedMs),
                    complete = false
                )
            )
        }
    }

    private fun effectiveTransportThroughputBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        if (transportThroughputWindowAccumulatedMs <= 0L || transportThroughputWindowAccumulatedBytes <= 0.0) {
            return transportThroughputBuckets.toList()
        }
        return buildList {
            addAll(transportThroughputBuckets)
            add(
                DebridBenchmarkThroughputBucketSample(
                    startOffsetMs = transportThroughputWindowIndex * throughputWindowMs,
                    durationMs = transportThroughputWindowAccumulatedMs,
                    bytesTransferred = transportThroughputWindowAccumulatedBytes.toLong(),
                    throughputMbps = transportThroughputWindowAccumulatedBytes.toMbps(transportThroughputWindowAccumulatedMs),
                    complete = false
                )
            )
        }
    }

    private fun hasTransportSamples(): Boolean {
        return transportThroughputBuckets.isNotEmpty() || transportThroughputWindowAccumulatedMs > 0L
    }

    private fun completedSustainedBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return if (hasTransportSamples()) {
            completedTransportThroughputBuckets()
        } else {
            completedThroughputBuckets()
        }
    }

    private fun effectiveSustainedBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return if (hasTransportSamples()) {
            effectiveTransportThroughputBuckets()
        } else {
            effectiveThroughputBuckets()
        }
    }

    private fun steadyStateCompletedSustainedBuckets(
        completedBuckets: List<DebridBenchmarkThroughputBucketSample>
    ): List<DebridBenchmarkThroughputBucketSample> {
        val steadyStateBuckets = completedBuckets.filter { it.startOffsetMs >= STEADY_STATE_WARMUP_MS }
        return if (steadyStateBuckets.isNotEmpty()) steadyStateBuckets else completedBuckets
    }

    private fun activeStallCount(): Int = if (hasTransportSamples()) transportStallCount else stallCount

    private fun activeMaxReadGapMs(): Long = if (hasTransportSamples()) transportMaxReadGapMs else maxReadGapMs

    private fun Long.toMbps(durationMs: Long): Double {
        return toDouble() * 8.0 / durationMs.toDouble() / 1_000.0
    }

    private fun Double.toMbps(durationMs: Long): Double {
        return this * 8.0 / durationMs.toDouble() / 1_000.0
    }

    private fun Double.isWithinRatio(other: Double, toleranceRatio: Double): Boolean {
        if (!isFinite() || !other.isFinite()) return false
        if (this == 0.0 && other == 0.0) return true
        val baseline = maxOf(kotlin.math.abs(this), kotlin.math.abs(other))
        if (baseline == 0.0) return true
        return kotlin.math.abs(this - other) / baseline <= toleranceRatio
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
