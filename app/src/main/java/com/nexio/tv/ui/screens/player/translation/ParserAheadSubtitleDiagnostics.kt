package com.nexio.tv.ui.screens.player.translation

import android.util.Log
import androidx.media3.common.Format

internal interface ParserAheadSubtitleDiagnostics {
    fun onEnqueued(event: EnqueueEvent) = Unit

    fun onDuplicateDrop() = Unit

    fun onOverflowDrop() = Unit

    fun onParserReset(format: Format) = Unit

    data class EnqueueEvent(
        val cueTimeUs: Long,
        val playbackPositionUs: Long,
        val queuedCount: Int,
        val channelCapacity: Int
    )

    companion object {
        fun logcat(): ParserAheadSubtitleDiagnostics = LogcatParserAheadSubtitleDiagnostics()

        fun disabled(): ParserAheadSubtitleDiagnostics = object : ParserAheadSubtitleDiagnostics {}
    }
}

private class LogcatParserAheadSubtitleDiagnostics : ParserAheadSubtitleDiagnostics {
    private var lastProgressLogMs = 0L
    private var enqueued = 0L
    private var duplicateDrops = 0L
    private var overflowDrops = 0L

    override fun onEnqueued(event: ParserAheadSubtitleDiagnostics.EnqueueEvent) {
        enqueued += 1
        val now = System.currentTimeMillis()
        if (now - lastProgressLogMs < LOG_INTERVAL_MS) return
        lastProgressLogMs = now
        val aheadMs = ((event.cueTimeUs - event.playbackPositionUs) / 1_000L).coerceAtLeast(0L)
        Log.i(
            TAG,
            "PARSER_AHEAD_SUBS event=progress enqueued=$enqueued " +
                "duplicateDrops=$duplicateDrops overflowDrops=$overflowDrops " +
                "queued=${event.queuedCount} capacity=${event.channelCapacity} " +
                "cueTimeMs=${event.cueTimeUs / 1_000L} aheadMs=$aheadMs"
        )
    }

    override fun onDuplicateDrop() {
        duplicateDrops += 1
    }

    override fun onOverflowDrop() {
        overflowDrops += 1
    }

    override fun onParserReset(format: Format) {
        Log.i(
            TAG,
            "PARSER_AHEAD_SUBS event=parser_reset format=${format.id.orEmpty()} " +
                "mime=${format.sampleMimeType.orEmpty()} lang=${format.language.orEmpty()}"
        )
    }

    private companion object {
        private const val TAG = "Nexio.SubtitleAhead"
        private const val LOG_INTERVAL_MS = 2_000L
    }
}
