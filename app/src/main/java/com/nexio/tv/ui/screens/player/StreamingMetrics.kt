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

    fun snapshot(): Map<String, Long> {
        return mapOf(
            "cache_hits" to cacheHits.get(),
            "cache_misses" to cacheMisses.get(),
            "fill_worker_bytes_written" to fillWorkerBytesWritten.get(),
            "fallback_reads_triggered" to fallbackReadsTriggered.get(),
            "coordinator_wait_timeouts" to coordinatorWaitTimeouts.get(),
            "fill_worker_pause_count" to fillWorkerPauseCount.get(),
            "urgent_fill_requests" to urgentFillRequests.get()
        )
    }
}
