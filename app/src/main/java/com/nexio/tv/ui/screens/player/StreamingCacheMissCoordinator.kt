package com.nexio.tv.ui.screens.player

internal interface PlaybackFallbackRangeChecker {
    fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean
}

internal class StreamingCacheMissCoordinator(
    private val rangeCoordinator: StreamingRangeCoordinator
) : PlaybackFallbackRangeChecker {

    interface UrgentFillHandler {
        fun prioritize(position: Long)

        fun awaitSpanCommitted(
            cacheKey: String,
            position: Long,
            minLength: Long,
            timeoutMs: Long
        ): Boolean

        fun estimatedBytesPerSecond(): Long
    }

    private val handlerLock = Any()

    @Volatile
    private var urgentFillHandler: UrgentFillHandler? = null

    fun attachUrgentFillHandler(handler: UrgentFillHandler) {
        synchronized(handlerLock) {
            urgentFillHandler = handler
        }
    }

    fun detachUrgentFillHandler(handler: UrgentFillHandler) {
        synchronized(handlerLock) {
            if (urgentFillHandler === handler) {
                urgentFillHandler = null
            }
        }
    }

    fun markFallbackOwned(start: Long, endExclusive: Long): String {
        StreamingMetrics.fallbackReadsTriggered.incrementAndGet()
        return rangeCoordinator.markFallbackOwned(start, endExclusive)
    }

    fun clearFallbackOwnership(token: String) {
        rangeCoordinator.clearFallbackOwnership(token)
    }

    override fun isOwnedByPlaybackFallback(start: Long, endExclusive: Long): Boolean {
        return rangeCoordinator.isOwnedByPlaybackFallback(start, endExclusive)
    }

    fun requestUrgentFill(
        cacheKey: String,
        position: Long,
        minLength: Long,
        timeoutMs: Long
    ): Boolean {
        StreamingMetrics.urgentFillRequests.incrementAndGet()
        val handler = urgentFillHandler ?: return false
        handler.prioritize(position)
        return handler.awaitSpanCommitted(
            cacheKey = cacheKey,
            position = position,
            minLength = minLength,
            timeoutMs = timeoutMs
        )
    }

    fun estimatedBytesPerSecond(): Long {
        return urgentFillHandler?.estimatedBytesPerSecond() ?: 0L
    }
}
