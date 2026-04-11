package com.nexio.tv.ui.screens.player

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache

@OptIn(UnstableApi::class)
internal class FillController(
    private val profile: ProviderProfile,
    private val cache: SimpleCache,
    private val cacheKey: String,
    private val playbackByteProvider: () -> Long
) {
    @Volatile
    var state: State = State.STARTUP
        private set

    fun onStart() {
        state = State.FILLING
    }

    fun onSeek() {
        state = State.SEEK
    }

    fun onMemoryWarning() {
        state = State.MEMORY_PRESSURE
        evictBehindPlayback(retainBehindBytes = 0L)
    }

    fun shouldPause(fillFrontier: Long): Boolean {
        if (state == State.MEMORY_PRESSURE || state == State.STOPPED) {
            return true
        }

        val aheadBytes = (fillFrontier - playbackByteProvider()).coerceAtLeast(0L)
        val shouldPause = when (state) {
            State.HORIZON_REACHED -> aheadBytes > profile.lowWaterBytes
            else -> aheadBytes >= profile.fillHorizonBytes
        }
        state = if (shouldPause) State.HORIZON_REACHED else State.FILLING
        if (shouldPause) StreamingMetrics.fillWorkerPauseCount.incrementAndGet()
        return shouldPause
    }

    fun evictBehindPlayback(retainBehindBytes: Long = profile.retainBehindBytes) {
        val evictBefore = (playbackByteProvider() - retainBehindBytes).coerceAtLeast(0L)
        val cachedSpans = cache.getCachedSpans(cacheKey)

        for (span in cachedSpans.toList()) {
            val spanEnd = span.position + span.length
            if (spanEnd <= evictBefore) {
                cache.removeSpan(span)
            }
        }
    }

    fun stop() {
        state = State.STOPPED
    }

    enum class State {
        STARTUP,
        FILLING,
        HORIZON_REACHED,
        SEEK,
        MEMORY_PRESSURE,
        STOPPED
    }
}
