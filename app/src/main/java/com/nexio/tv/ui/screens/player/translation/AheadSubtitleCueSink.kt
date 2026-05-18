package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.extractor.text.CuesWithTiming

internal data class AheadSubtitleCue(
    val format: Format,
    val cues: CuesWithTiming
)

internal interface AheadSubtitleCueSink {
    fun isEnabled(format: Format): Boolean

    fun enqueue(format: Format, cues: CuesWithTiming)

    fun onParserReset(format: Format)
}
