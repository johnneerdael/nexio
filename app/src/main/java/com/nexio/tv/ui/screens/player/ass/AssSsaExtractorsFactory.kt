package com.nexio.tv.ui.screens.player.ass

import android.net.Uri
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.text.SubtitleParser

internal class AssSsaExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val sink: AssSsaSampleSink
) : ExtractorsFactory {
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(
        textTrackTranscodingEnabled: Boolean
    ): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(
        subtitleParserFactory: SubtitleParser.Factory
    ): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(
        codecsToParseWithinGopSampleDependencies: Int
    ): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(
            codecsToParseWithinGopSampleDependencies
        )
        return this
    }

    override fun createExtractors() = delegate.createExtractors().withAssSsaMatroskaExtractor()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>
    ) = delegate.createExtractors(uri, responseHeaders).withAssSsaMatroskaExtractor()

    private fun Array<Extractor>.withAssSsaMatroskaExtractor(): Array<Extractor> {
        forEachIndexed { index, extractor ->
            if (extractor is MatroskaExtractor && extractor !is AssSsaMatroskaExtractor) {
                this[index] = AssSsaMatroskaExtractor(sink)
            }
        }
        return this
    }
}
