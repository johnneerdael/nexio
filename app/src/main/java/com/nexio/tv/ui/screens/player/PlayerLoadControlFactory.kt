package com.nexio.tv.ui.screens.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import kotlin.math.max

@androidx.annotation.OptIn(UnstableApi::class)
internal object PlayerLoadControlFactory {
    const val DEFAULT_ESTIMATED_BITRATE_BPS = 120_000_000L
    const val BUFFER_FOR_PLAYBACK_MS = 2_500
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 5_000

    data class LoadControlSpec(
        val minBufferMs: Int,
        val maxBufferMs: Int,
        val bufferForPlaybackMs: Int,
        val bufferForPlaybackAfterRebufferMs: Int,
        val targetBufferBytes: Int,
        val prioritizeTimeOverSizeThresholdsForStreaming: Boolean
    )

    fun buildDefaultLoadControl(): DefaultLoadControl {
        return DefaultLoadControl.Builder().build()
    }

    fun buildBudgetedLoadControl(
        effectiveSampleQueueBytes: Long,
        estimatedBitrateBps: Long = DEFAULT_ESTIMATED_BITRATE_BPS
    ): DefaultLoadControl {
        val spec = buildBudgetedSpec(
            effectiveSampleQueueBytes = effectiveSampleQueueBytes,
            estimatedBitrateBps = estimatedBitrateBps
        )
        return DefaultLoadControl.Builder()
            .setBufferDurationsMsForStreaming(
                spec.minBufferMs,
                spec.maxBufferMs,
                spec.bufferForPlaybackMs,
                spec.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(spec.targetBufferBytes)
            .setPrioritizeTimeOverSizeThresholdsForStreaming(
                spec.prioritizeTimeOverSizeThresholdsForStreaming
            )
            .build()
    }

    fun buildBudgetedSpec(
        effectiveSampleQueueBytes: Long,
        estimatedBitrateBps: Long = DEFAULT_ESTIMATED_BITRATE_BPS
    ): LoadControlSpec {
        val bytesPerSecond = (estimatedBitrateBps / 8L).coerceAtLeast(1L)
        val maxBufferSeconds = (effectiveSampleQueueBytes / bytesPerSecond).coerceIn(8L, 30L)
        val maxBufferMs = (maxBufferSeconds * 1_000L).toInt()
        val minBufferMs = max(
            (maxBufferSeconds * 500L).toInt(),
            BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
        )
        return LoadControlSpec(
            minBufferMs = minBufferMs,
            maxBufferMs = maxBufferMs,
            bufferForPlaybackMs = BUFFER_FOR_PLAYBACK_MS,
            bufferForPlaybackAfterRebufferMs = BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
            targetBufferBytes = effectiveSampleQueueBytes
                .coerceIn(MemoryBudget.MIN_SAMPLE_QUEUE_BYTES, MemoryBudget.MAX_SAMPLE_QUEUE_BYTES)
                .toInt(),
            prioritizeTimeOverSizeThresholdsForStreaming = false
        )
    }
}
