package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.media3.exoplayer.DefaultLoadControl
import com.nexio.tv.data.local.PlayerSettings

internal object PlayerLoadControlFactory {
    fun build(context: Context, settings: PlayerSettings): DefaultLoadControl {
        val buffer = settings.bufferSettings
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                buffer.minBufferMs,
                buffer.maxBufferMs,
                buffer.bufferForPlaybackMs,
                buffer.bufferForPlaybackAfterRebufferMs
            )
            .setTargetBufferBytes(resolveTargetBufferBytes(context, settings))
            .setPrioritizeTimeOverSizeThresholds(false)
            .setBackBuffer(
                buffer.backBufferDurationMs,
                buffer.retainBackBufferFromKeyframe
            )
            .build()
    }

    fun resolveTargetBufferBytes(context: Context, settings: PlayerSettings): Int {
        return resolveTargetBufferBytes(
            settings = settings,
            effectiveSampleQueueBytes = MemoryBudget(context).effectiveSampleQueueBytes
        )
    }

    internal fun resolveTargetBufferBytes(
        settings: PlayerSettings,
        effectiveSampleQueueBytes: Long
    ): Int {
        val safeBudgetBytes = effectiveSampleQueueBytes
            .coerceAtLeast(1L)
            .coerceAtMost(MemoryBudget.MAX_SAMPLE_QUEUE_BYTES)
        val configuredTargetBytes = settings.bufferSettings.targetBufferSizeMb
            .takeIf { it > 0 }
            ?.toLong()
            ?.times(1024L * 1024L)
        val targetBytes = configuredTargetBytes
            ?.coerceAtMost(safeBudgetBytes)
            ?: safeBudgetBytes
        return targetBytes.toInt()
    }
}
