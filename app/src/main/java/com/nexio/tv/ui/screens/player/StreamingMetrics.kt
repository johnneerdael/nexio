package com.nexio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicLong

internal object StreamingMetrics {
    val cacheHits = AtomicLong(0L)
    val cacheMisses = AtomicLong(0L)
    val fillWorkerBytesWritten = AtomicLong(0L)
    val fallbackReadsTriggered = AtomicLong(0L)
    val coordinatorWaitTimeouts = AtomicLong(0L)
    val fillWorkerPauseCount = AtomicLong(0L)
    val urgentFillRequests = AtomicLong(0L)
    val coverageFallbackSegmentOpens = AtomicLong(0L)
    val coverageFallbackBytesRequested = AtomicLong(0L)
    val coverageUrgentFillAttempts = AtomicLong(0L)
    val coverageUrgentFillSuccesses = AtomicLong(0L)
    val fillWorkerChunkStarts = AtomicLong(0L)
    val fillWorkerFallbackOwnedSkips = AtomicLong(0L)

    fun snapshot(): Map<String, Long> {
        return mapOf(
            "cache_hits" to cacheHits.get(),
            "cache_misses" to cacheMisses.get(),
            "fill_worker_bytes_written" to fillWorkerBytesWritten.get(),
            "fallback_reads_triggered" to fallbackReadsTriggered.get(),
            "coordinator_wait_timeouts" to coordinatorWaitTimeouts.get(),
            "fill_worker_pause_count" to fillWorkerPauseCount.get(),
            "urgent_fill_requests" to urgentFillRequests.get(),
            "coverage_fallback_segment_opens" to coverageFallbackSegmentOpens.get(),
            "coverage_fallback_bytes_requested" to coverageFallbackBytesRequested.get(),
            "coverage_urgent_fill_attempts" to coverageUrgentFillAttempts.get(),
            "coverage_urgent_fill_successes" to coverageUrgentFillSuccesses.get(),
            "fill_worker_chunk_starts" to fillWorkerChunkStarts.get(),
            "fill_worker_fallback_owned_skips" to fillWorkerFallbackOwnedSkips.get()
        )
    }

    fun reset() {
        cacheHits.set(0L)
        cacheMisses.set(0L)
        fillWorkerBytesWritten.set(0L)
        fallbackReadsTriggered.set(0L)
        coordinatorWaitTimeouts.set(0L)
        fillWorkerPauseCount.set(0L)
        urgentFillRequests.set(0L)
        coverageFallbackSegmentOpens.set(0L)
        coverageFallbackBytesRequested.set(0L)
        coverageUrgentFillAttempts.set(0L)
        coverageUrgentFillSuccesses.set(0L)
        fillWorkerChunkStarts.set(0L)
        fillWorkerFallbackOwnedSkips.set(0L)
    }
}
