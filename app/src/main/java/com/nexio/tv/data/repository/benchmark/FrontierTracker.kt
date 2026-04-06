package com.nexio.tv.data.repository.benchmark

import java.util.BitSet

/**
 * Tracks contiguous playable byte progress during parallel chunk downloads.
 *
 * Maintains a BitSet of 128KB page completions and computes the contiguous frontier —
 * the highest byte offset where all preceding pages are complete from byte 0. Called
 * concurrently from ParallelRangeDataSource's executor thread pool (2–4 threads).
 */
class FrontierTracker(
    private val pageSize: Int = 128 * 1024
) {
    private val lock = Any()
    private val pages = BitSet()
    private var contiguousFrontierBytes: Long = 0L
    private var lastFrontierPageIndex: Int = 0
    private val events = mutableListOf<FrontierEvent>()
    private var firstEventTMs: Long? = null
    private var lastEventTMs: Long? = null

    /**
     * Records that bytes were downloaded for the given chunk. Converts the chunk-relative
     * byte range to absolute page indices and marks those pages complete, then attempts
     * to advance the contiguous frontier.
     *
     * Pages touching a chunk boundary are treated as complete (see design note in class doc).
     * Calls with [bytesRead] <= 0 are no-ops.
     */
    fun onBytesDownloaded(
        chunkIndex: Long,
        chunkSize: Long,
        offsetInChunk: Long,
        bytesRead: Int,
        tMs: Long
    ) {
        if (bytesRead <= 0) return
        val absoluteOffset = chunkIndex * chunkSize + offsetInChunk
        val firstPage = (absoluteOffset / pageSize).toInt()
        val lastPage = ((absoluteOffset + bytesRead - 1) / pageSize).toInt()
        synchronized(lock) {
            pages.set(firstPage, lastPage + 1)
            advanceFrontier(tMs)
        }
    }

    /**
     * Returns a snapshot of all frontier advancement events collected so far.
     */
    fun frontierEvents(): List<FrontierEvent> {
        synchronized(lock) {
            return events.toList()
        }
    }

    /**
     * Computes summary metrics from the collected frontier event timeline.
     *
     * [elapsedMs] is the total benchmark duration used to compute the advance rate.
     */
    fun computeFrontierMetrics(elapsedMs: Long): FrontierMetrics {
        synchronized(lock) {
            val finalBytes = contiguousFrontierBytes
            val advanceRateMbps = if (elapsedMs > 0L) {
                finalBytes * 8.0 / elapsedMs.toDouble() / 1_000.0
            } else {
                null
            }

            var stallCount = 0
            var maxStallMs = 0L
            for (i in 1 until events.size) {
                val gapMs = events[i].tMs - events[i - 1].tMs
                if (gapMs > maxStallMs) maxStallMs = gapMs
                if (gapMs > STALL_THRESHOLD_MS) stallCount++
            }

            return FrontierMetrics(
                totalFrontierEvents = events.size,
                finalFrontierBytes = finalBytes,
                frontierAdvanceRateMbps = advanceRateMbps,
                frontierStallCount = stallCount,
                maxFrontierStallMs = maxStallMs
            )
        }
    }

    /**
     * Scans forward from the last known frontier page index, advancing [contiguousFrontierBytes]
     * as far as pages are continuously set. Emits a [FrontierEvent] when the frontier moves.
     *
     * Must be called with [lock] held.
     */
    private fun advanceFrontier(tMs: Long) {
        val previousFrontierBytes = contiguousFrontierBytes
        var pageIndex = lastFrontierPageIndex
        while (pages.get(pageIndex)) {
            pageIndex++
        }
        val newFrontierBytes = pageIndex.toLong() * pageSize.toLong()
        lastFrontierPageIndex = pageIndex
        contiguousFrontierBytes = newFrontierBytes

        val delta = newFrontierBytes - previousFrontierBytes
        if (delta > 0L) {
            if (firstEventTMs == null) firstEventTMs = tMs
            lastEventTMs = tMs
            events.add(
                FrontierEvent(
                    tMs = tMs,
                    contiguousFrontierBytes = newFrontierBytes,
                    deltaContiguousBytes = delta
                )
            )
        }
    }

    private companion object {
        private const val STALL_THRESHOLD_MS = 2_000L
    }
}
