package com.nexio.tv.ui.screens.player

import android.os.SystemClock

internal class BandwidthMonitor(
    private val windowMs: Long = 5_000L,
    private val clockMs: () -> Long = SystemClock::elapsedRealtime,
    capacity: Int = DEFAULT_CAPACITY
) {
    private data class Sample(
        var timestampMs: Long = 0L,
        var bytes: Long = 0L
    )

    private val samples = Array(capacity.coerceAtLeast(2)) { Sample() }
    private val lock = Any()
    private var startIndex = 0
    private var size = 0
    private var lastTimestampMs = Long.MIN_VALUE

    fun onBytesTransferred(bytes: Long) {
        if (bytes <= 0L) return
        synchronized(lock) {
            val now = clockMs()
            if (now < lastTimestampMs) return
            lastTimestampMs = now
            pruneLocked(now)
            appendLocked(now, bytes)
        }
    }

    fun estimatedBytesPerSecond(): Long {
        synchronized(lock) {
            pruneLocked(clockMs())
            if (size <= 1) return 0L

            val first = samples[startIndex]
            val last = samples[index(size - 1)]
            val elapsedMs = last.timestampMs - first.timestampMs
            if (elapsedMs <= 0L) return 0L

            var totalBytes = 0L
            for (i in 0 until size) {
                totalBytes += samples[index(i)].bytes
            }
            return totalBytes * 1_000L / elapsedMs
        }
    }

    private fun appendLocked(timestampMs: Long, bytes: Long) {
        if (size == samples.size) {
            startIndex = (startIndex + 1) % samples.size
            size--
        }

        val insertIndex = index(size)
        samples[insertIndex].timestampMs = timestampMs
        samples[insertIndex].bytes = bytes
        size++
    }

    private fun pruneLocked(nowMs: Long) {
        val cutoffMs = nowMs - windowMs
        while (size > 0 && samples[startIndex].timestampMs < cutoffMs) {
            startIndex = (startIndex + 1) % samples.size
            size--
        }
    }

    private fun index(offset: Int): Int = (startIndex + offset) % samples.size

    companion object {
        private const val DEFAULT_CAPACITY = 256
    }
}
