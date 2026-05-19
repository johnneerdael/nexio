package com.nexio.tv.ui.screens.player.translation

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@OptIn(UnstableApi::class)
internal class TappingSubtitleParserFactory(
    private val delegate: SubtitleParser.Factory = DefaultSubtitleParserFactory(),
    private val cueSink: AheadSubtitleCueSink
) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean {
        return delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return delegate.getCueReplacementBehavior(format)
    }

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        return object : SubtitleParser {
            override fun parse(
                data: ByteArray,
                offset: Int,
                length: Int,
                outputOptions: SubtitleParser.OutputOptions,
                output: Consumer<CuesWithTiming>
            ) {
                parser.parse(data, offset, length, outputOptions) { cues ->
                    if (cueSink.isEnabled(format)) {
                        cueSink.enqueue(format, cues)
                    }
                    output.accept(cues)
                }
            }

            override fun reset() {
                parser.reset()
                cueSink.onParserReset(format)
            }

            override fun getCueReplacementBehavior(): Int {
                return parser.cueReplacementBehavior
            }
        }
    }
}
