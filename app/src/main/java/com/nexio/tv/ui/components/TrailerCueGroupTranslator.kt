package com.nexio.tv.ui.components

import androidx.media3.common.Format
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.CueGroupSubtitleTranslator
import com.nexio.tv.ui.screens.player.BuiltInSubtitleCueTranslator

/**
 * Trailer-specific [CueGroupSubtitleTranslator] wrapper around the stream
 * player's `BuiltInSubtitleCueTranslator`. The only behavioral difference
 * is [getPrefetchDurationUs] — trailers use a short prefetch horizon
 * (default 0) so Media3's TextRenderer only translates near-term cues
 * before letting playback start. Streams keep the unbounded prefetch
 * because their preparation pipeline isn't blocked by the
 * SingleSampleMediaSource sideload that trailers use.
 *
 * Without this wrapper, the side-loaded subtitle source can't report
 * prepared until the entire SRT track has been translated, which blocks
 * the trailer's MergingMediaSource preparation and prevents playback
 * from starting until every cue batch returns from the AI provider.
 */
internal class TrailerCueGroupTranslator(
    private val delegate: BuiltInSubtitleCueTranslator,
    private val trailerPrefetchDurationUs: Long = 0L
) : CueGroupSubtitleTranslator {

    override fun getConfigurationToken(format: Format): String? =
        delegate.getConfigurationToken(format)

    override fun getPrefetchDurationUs(): Long = trailerPrefetchDurationUs

    override fun translate(
        format: Format,
        cueGroups: List<CueGroup>,
        callback: CueGroupSubtitleTranslator.TranslationCallback
    ) {
        delegate.translate(format, cueGroups, callback)
    }
}
