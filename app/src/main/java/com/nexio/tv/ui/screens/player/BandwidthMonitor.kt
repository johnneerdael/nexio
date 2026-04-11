package com.nexio.tv.ui.screens.player

import android.os.SystemClock
import java.util.concurrent.ConcurrentLinkedDeque

internal class BandwidthMonitor(
    private val windowMs: Long = 5_000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime
) {
    private data class Sample(
        val timestampMs: Long,
        val bytes: Long
    )

    private val samples = ConcurrentLinkedDeque<Sample>()
    private val lock = Any()

    fun onBytesTransferred(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) {
            val now = clockMs()
            pruneAndNormalizeLocked(now)
            insertSampleLocked(Sample(timestampMs = now, bytes = bytes))
        }
    }

    fun estimatedBytesPerSecond(): Long {
        synchronized(lock) {
            pruneAndNormalizeLocked(clockMs())

            val windowSamples = samples.toList()
            if (windowSamples.isEmpty()) return 0L

            val elapsedMs = windowSamples.last().timestampMs - windowSamples.first().timestampMs
            if (elapsedMs <= 0L) return 0L

            val totalBytes = windowSamples.fold(0L) { acc, sample -> acc + sample.bytes }
            return totalBytes * 1_000L / elapsedMs
        }
    }

    private fun pruneAndNormalizeLocked(nowMs: Long) {
        val cutoffMs = nowMs - windowMs
        val retainedSamples = samples
            .asSequence()
            .filter { it.timestampMs >= cutoffMs }
            .sortedBy { it.timestampMs }
            .toList()

        samples.clear()
        retainedSamples.forEach(samples::addLast)
    }

    private fun insertSampleLocked(sample: Sample) {
        val orderedSamples = samples
            .asSequence()
            .plus(sample)
            .sortedBy { it.timestampMs }
            .toList()

        samples.clear()
        orderedSamples.forEach(samples::addLast)
    }
}
