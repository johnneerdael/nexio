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
        private const val COLLECTOR_VERSION = 8
        private const val SAMPLING_MODE_FIXED_TIME_BUCKET = "fixed_time_bucket"
        private const val CONSISTENCY_TOLERANCE_RATIO = 0.10
        private const val STEADY_STATE_WARMUP_MS = 10_000L
        private const val DECISION_CONSUMER_WINDOW_MS = 5_000L
        private const val DECISION_TRANSIENT_DROP_FLOOR_RATIO = 0.25
        private const val DECISION_MIN_WINDOW_COUNT_FOR_FLOOR = 4
        private const val CONSUMER_TRANSIENT_DROP_MAX_RUN_LENGTH = 3
        private const val CACHE_HEADROOM_INITIAL_MS = 10_000L
        private const val CACHE_HEADROOM_MAX_MS = 45_000L
        private const val CACHE_HEADROOM_DRAINING_FLOOR_MS = 5_000L
    }

    private var requestStartedAtMs: Long? = null
    private var firstByteAtMs: Long? = null
    private var totalBytesRead = 0L
    private var lastSampleAtMs: Long? = null
    private var lastSampleBytesRead = 0L
    private var pendingOuterDeltaBytes = 0.0
    private val consumerThroughputSamples = mutableListOf<ConsumerThroughputSample>()
    private var stallCount = 0
    private var maxReadGapMs = 0L
    private val throughputBuckets = mutableListOf<DebridBenchmarkThroughputBucketSample>()
    private var throughputStreamStartAtMs: Long? = null
    private var throughputWindowIndex = 0L
    private var throughputWindowAccumulatedBytes = 0.0
    private var throughputWindowAccumulatedMs = 0L
    private var transportLastSampleAtMs: Long? = null
    private var transportStallCount = 0
    private var transportMaxReadGapMs = 0L
    private val transportThroughputBuckets = mutableListOf<DebridBenchmarkThroughputBucketSample>()
    private var transportThroughputStreamStartAtMs: Long? = null
    private var transportThroughputWindowIndex = 0L
    private var transportThroughputWindowAccumulatedBytes = 0.0
    private val seekSamples = mutableListOf<DebridBenchmarkSeekSample>()
    private val transportStateLock = Any()
    private val frontierTracker = FrontierTracker()

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
        val completedBeforeSample = shouldComplete()

        if (previousSampleAtMs != null) {
            val deltaMs = (sampleTimeMs - previousSampleAtMs).coerceAtLeast(0L)
            val deltaBytes = (clampedBytesRead - lastSampleBytesRead).coerceAtLeast(0L)
            pendingOuterDeltaBytes += deltaBytes.toDouble()
            if (deltaMs > 0L) {
                if (!completedBeforeSample) {
                    recordConsumerThroughputSample(
                        deltaBytes = deltaBytes.toDouble(),
                        deltaMs = deltaMs
                    )
                }
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
                if (!completedBeforeSample) {
                    recordConsumerThroughputSample(
                        deltaBytes = clampedBytesRead.toDouble(),
                        deltaMs = deltaMs
                    )
                }
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

        synchronized(transportStateLock) {
            val sampleTimeMs = sampleAtMs.coerceAtLeast(0L)
            val previousSampleAtMs = transportLastSampleAtMs
            if (previousSampleAtMs != null) {
                val gapMs = (sampleTimeMs - previousSampleAtMs).coerceAtLeast(0L)
                transportMaxReadGapMs = maxOf(transportMaxReadGapMs, gapMs)
                if (gapMs >= stallThresholdMs) {
                    transportStallCount += 1
                }
            }
            recordTransportSample(
                bytesRead = clampedBytesRead.toDouble(),
                sampleAtMs = sampleTimeMs
            )
            transportLastSampleAtMs = sampleTimeMs
        }
    }

    fun recordFrontierProgress(
        chunkIndex: Long,
        chunkSize: Long,
        offsetInChunk: Long,
        bytesRead: Int,
        tMs: Long
    ) {
        frontierTracker.onBytesDownloaded(chunkIndex, chunkSize, offsetInChunk, bytesRead, tMs)
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
        val decisionBuckets = buildDecisionBuckets(completedBuckets)
        val steadyStateDecisionBuckets = steadyStateCompletedSustainedBuckets(decisionBuckets)
        val sortedWindows = decisionBuckets.map { it.throughputMbps }.sorted()
        val steadyStateWindows = steadyStateDecisionBuckets.map { it.throughputMbps }.sorted()
        val decisionWindows = filterTransientDropWindows(sortedWindows)
        val steadyStateDecisionWindows = filterTransientDropWindows(steadyStateWindows)
        val consumerP10 = consumerP10Mbps()
        val bucketP10 = steadyStateDecisionWindows.percentileNearestRank(0.10)
        val average = completedBuckets.takeIf { it.isNotEmpty() }?.map { it.throughputMbps }?.average()
        val steadyStateAverage = steadyStateDecisionWindows.takeIf { it.isNotEmpty() }?.average()
        val steadyStateReference = listOfNotNull(
            steadyStateDecisionWindows.percentileNearestRank(0.50),
            steadyStateAverage
        ).minOrNull()
        val cacheEvidence = analyzeCacheAwareDeficits(
            buckets = steadyStateCompletedSustainedBuckets(completedBuckets),
            referenceMbps = steadyStateReference
        )
        val completedBytesTransferred = completedBuckets.sumOf { it.bytesTransferred }
        val completedElapsedMs = completedBuckets.sumOf { it.durationMs }
        val derivedAverage = completedBytesTransferred
            .takeIf { completedElapsedMs > 0L }
            ?.toMbps(completedElapsedMs)
        val stddev = steadyStateDecisionWindows.standardDeviation(steadyStateAverage)
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
            decisionWindowMs = decisionWindowMs(),
            averageThroughputMbps = average,
            derivedAverageThroughputMbps = derivedAverage,
            actionable = actionable,
            p10ThroughputMbps = consumerP10 ?: bucketP10,
            p50ThroughputMbps = steadyStateDecisionWindows.percentileNearestRank(0.50),
            peakThroughputMbps = decisionWindows.maxOrNull(),
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
            steadyStateReferenceMbps = steadyStateReference,
            cacheAbsorbableDeficitCount = cacheEvidence.absorbableDeficitCount,
            cacheDrainingDeficitCount = cacheEvidence.drainingDeficitCount,
            estimatedMinCacheHeadroomMs = cacheEvidence.minHeadroomMs,
            bytesTransferred = completedBytesTransferred,
            elapsedMs = completedElapsedMs,
            frontierMetrics = frontierTracker.computeFrontierMetrics(completedElapsedMs)
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
            seekSamples = seekSamples.toList(),
            frontierEvents = frontierTracker.frontierEvents()
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
                // Defensive guard: should be unreachable now that the
                // window index advances on every boundary crossing below.
                // If we ever land here without progress, force-advance the
                // window index to break out instead of spinning forever.
                if (segmentEndMs <= cursorMs) {
                    flushCurrentThroughputWindow(complete = true)
                }
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

            // Flush whenever the cursor reaches the current window's end —
            // even if the window is only partially filled. The previous
            // `accumulatedMs >= windowMs` condition could leave a partial
            // window unflushed, then the next iteration would see the same
            // (unchanged) window index and spin forever because
            // `segmentDurationMs` would collapse to 0. (Reproduced by the
            // `finishSustained keeps rd probe consumer p10 resilient to a
            // single zero sample` test in `DebridBenchmarkMetricsCollectorTest`.)
            if (cursorMs == currentWindowEnd) {
                flushCurrentThroughputWindow(complete = true)
            }
        }
    }

    private fun recordTransportSample(
        bytesRead: Double,
        sampleAtMs: Long
    ) {
        if (bytesRead <= 0.0) return
        ensureTransportThroughputWindowStarted(sampleAtMs)
        val streamStartAtMs = requireNotNull(transportThroughputStreamStartAtMs)
        val offsetMs = (sampleAtMs - streamStartAtMs).coerceAtLeast(0L)
        val targetWindowIndex = offsetMs / throughputWindowMs

        while (transportThroughputWindowIndex < targetWindowIndex) {
            flushCurrentTransportThroughputWindow(complete = true)
        }

        transportThroughputWindowAccumulatedBytes += bytesRead
    }

    private fun recordConsumerThroughputSample(
        deltaBytes: Double,
        deltaMs: Long
    ) {
        if (deltaBytes <= 0.0 || deltaMs <= 0L) return
        consumerThroughputSamples += ConsumerThroughputSample(
            throughputMbps = deltaBytes.toMbps(deltaMs)
        )
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
        val durationMs = if (complete) {
            throughputWindowMs
        } else {
            val streamStartAtMs = transportThroughputStreamStartAtMs ?: return
            val currentWindowStart = streamStartAtMs + (transportThroughputWindowIndex * throughputWindowMs)
            val lastSampleAtMs = transportLastSampleAtMs ?: return
            (lastSampleAtMs - currentWindowStart).coerceIn(0L, throughputWindowMs)
        }
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
        if (transportThroughputWindowAccumulatedBytes <= 0.0) {
            return transportThroughputBuckets.toList()
        }
        val streamStartAtMs = transportThroughputStreamStartAtMs ?: return transportThroughputBuckets.toList()
        val lastSampleAtMs = transportLastSampleAtMs ?: return transportThroughputBuckets.toList()
        val currentWindowStart = streamStartAtMs + (transportThroughputWindowIndex * throughputWindowMs)
        val durationMs = (lastSampleAtMs - currentWindowStart).coerceIn(0L, throughputWindowMs)
        if (durationMs <= 0L) return transportThroughputBuckets.toList()
        return buildList {
            addAll(transportThroughputBuckets)
            add(
                DebridBenchmarkThroughputBucketSample(
                    startOffsetMs = transportThroughputWindowIndex * throughputWindowMs,
                    durationMs = durationMs,
                    bytesTransferred = transportThroughputWindowAccumulatedBytes.toLong(),
                    throughputMbps = transportThroughputWindowAccumulatedBytes.toMbps(durationMs),
                    complete = false
                )
            )
        }
    }

    private fun hasTransportSamples(): Boolean {
        return transportThroughputBuckets.isNotEmpty() || transportThroughputWindowAccumulatedBytes > 0.0
    }

    private fun completedSustainedBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return completedThroughputBuckets()
    }

    private fun effectiveSustainedBuckets(): List<DebridBenchmarkThroughputBucketSample> {
        return effectiveThroughputBuckets()
    }

    private fun steadyStateCompletedSustainedBuckets(
        completedBuckets: List<DebridBenchmarkThroughputBucketSample>
    ): List<DebridBenchmarkThroughputBucketSample> {
        val steadyStateBuckets = completedBuckets.filter { it.startOffsetMs >= STEADY_STATE_WARMUP_MS }
        return if (steadyStateBuckets.isNotEmpty()) steadyStateBuckets else completedBuckets
    }

    private fun buildDecisionBuckets(
        completedBuckets: List<DebridBenchmarkThroughputBucketSample>
    ): List<DebridBenchmarkThroughputBucketSample> {
        val decisionWindowMs = decisionWindowMs()
        if (decisionWindowMs <= throughputWindowMs) return completedBuckets

        val aggregatedBuckets = mutableListOf<DebridBenchmarkThroughputBucketSample>()
        var currentWindow = mutableListOf<DebridBenchmarkThroughputBucketSample>()
        var currentDurationMs = 0L

        completedBuckets.forEach { bucket ->
            currentWindow += bucket
            currentDurationMs += bucket.durationMs

            if (currentDurationMs >= decisionWindowMs) {
                val durationMs = currentWindow.sumOf { it.durationMs }
                val bytesTransferred = currentWindow.sumOf { it.bytesTransferred }
                aggregatedBuckets += DebridBenchmarkThroughputBucketSample(
                    startOffsetMs = currentWindow.first().startOffsetMs,
                    durationMs = durationMs,
                    bytesTransferred = bytesTransferred,
                    throughputMbps = bytesTransferred.toMbps(durationMs),
                    complete = true
                )
                currentWindow = mutableListOf()
                currentDurationMs = 0L
            }
        }

        return aggregatedBuckets.ifEmpty { completedBuckets }
    }

    private fun decisionWindowMs(): Long {
        return maxOf(throughputWindowMs, DECISION_CONSUMER_WINDOW_MS)
    }

    private fun consumerP10Mbps(): Double? {
        val timeOrdered = consumerThroughputSamples.map { it.throughputMbps }
        val filtered = filterTransientConsumerDropRuns(timeOrdered)
        return filtered.sorted().percentileNearestRank(0.10)
    }

    private fun filterTransientConsumerDropRuns(samples: List<Double>): List<Double> {
        // A transient drop is a contiguous run of up to
        // CONSUMER_TRANSIENT_DROP_MAX_RUN_LENGTH samples whose throughput is
        // under DECISION_TRANSIENT_DROP_FLOOR_RATIO of the overall median.
        // Sustained collapses (longer runs) are preserved so they still
        // drag the autoplay floor down.
        if (samples.isEmpty()) return samples
        val sorted = samples.sorted()
        val median = sorted.percentileNearestRank(0.50) ?: return samples
        if (!median.isFinite() || median <= 0.0) return samples
        val floor = median * DECISION_TRANSIENT_DROP_FLOOR_RATIO
        val result = mutableListOf<Double>()
        var i = 0
        while (i < samples.size) {
            if (samples[i] >= floor) {
                result += samples[i]
                i += 1
            } else {
                var runEnd = i
                while (runEnd < samples.size && samples[runEnd] < floor) {
                    runEnd += 1
                }
                val runLength = runEnd - i
                if (runLength > CONSUMER_TRANSIENT_DROP_MAX_RUN_LENGTH) {
                    for (j in i until runEnd) result += samples[j]
                }
                i = runEnd
            }
        }
        return result
    }

    private fun filterTransientDropWindows(windows: List<Double>): List<Double> {
        if (windows.size < DECISION_MIN_WINDOW_COUNT_FOR_FLOOR) return windows
        val median = windows.percentileNearestRank(0.50) ?: return windows
        if (!median.isFinite() || median <= 0.0) return windows
        val floor = median * DECISION_TRANSIENT_DROP_FLOOR_RATIO
        val filtered = windows.filter { it >= floor }
        return if (filtered.size >= DECISION_MIN_WINDOW_COUNT_FOR_FLOOR) filtered else windows
    }

    private fun analyzeCacheAwareDeficits(
        buckets: List<DebridBenchmarkThroughputBucketSample>,
        referenceMbps: Double?
    ): CacheAwareDeficitEvidence {
        if (buckets.isEmpty() || referenceMbps == null || !referenceMbps.isFinite() || referenceMbps <= 0.0) {
            return CacheAwareDeficitEvidence(
                absorbableDeficitCount = 0,
                drainingDeficitCount = 0,
                minHeadroomMs = CACHE_HEADROOM_INITIAL_MS
            )
        }

        var headroomMs = CACHE_HEADROOM_INITIAL_MS.toDouble()
        var minHeadroomMs = headroomMs
        var absorbableDeficitCount = 0
        var drainingDeficitCount = 0

        buckets.forEach { bucket ->
            val ratio = bucket.throughputMbps / referenceMbps
            val deltaHeadroomMs = (ratio - 1.0) * bucket.durationMs.toDouble()
            headroomMs = (headroomMs + deltaHeadroomMs)
                .coerceIn(0.0, CACHE_HEADROOM_MAX_MS.toDouble())
            minHeadroomMs = minOf(minHeadroomMs, headroomMs)

            if (ratio < 1.0) {
                if (headroomMs >= CACHE_HEADROOM_DRAINING_FLOOR_MS.toDouble()) {
                    absorbableDeficitCount += 1
                } else {
                    drainingDeficitCount += 1
                }
            }
        }

        return CacheAwareDeficitEvidence(
            absorbableDeficitCount = absorbableDeficitCount,
            drainingDeficitCount = drainingDeficitCount,
            minHeadroomMs = minHeadroomMs.toLong()
        )
    }

    private fun activeStallCount(): Int = stallCount

    private fun activeMaxReadGapMs(): Long = maxReadGapMs

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

private data class ConsumerThroughputSample(
    val throughputMbps: Double
)

private data class CacheAwareDeficitEvidence(
    val absorbableDeficitCount: Int,
    val drainingDeficitCount: Int,
    val minHeadroomMs: Long
)
