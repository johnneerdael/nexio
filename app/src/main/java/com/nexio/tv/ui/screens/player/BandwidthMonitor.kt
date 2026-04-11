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

    fun onBytesTransferred(bytes: Long) {
        if (bytes <= 0L) return
        samples.addLast(Sample(timestampMs = clockMs(), bytes = bytes))
    }

    fun estimatedBytesPerSecond(): Long {
        val now = clockMs()
        val cutoffMs = now - windowMs
        evictOlderThan(cutoffMs)

        val windowSamples = samples.toList()
        if (windowSamples.isEmpty()) return 0L

        val elapsedMs = windowSamples.last().timestampMs - windowSamples.first().timestampMs
        if (elapsedMs <= 0L) return 0L

        val totalBytes = windowSamples.fold(0L) { acc, sample -> acc + sample.bytes }
        return totalBytes * 1_000L / elapsedMs
    }

    private fun evictOlderThan(cutoffMs: Long) {
        while (true) {
            val head = samples.peekFirst() ?: return
            if (head.timestampMs >= cutoffMs) return
            samples.pollFirst()
        }
    }
}
