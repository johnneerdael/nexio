package com.nexio.tv.ui.screens.player.ass

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser

@OptIn(UnstableApi::class)
internal class AssNoOpSubtitleParserFactory : SubtitleParser.Factory {
    private val delegate = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean {
        return format.isAssSsaFormat() || delegate.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        return if (format.isAssSsaFormat()) {
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        } else {
            delegate.getCueReplacementBehavior(format)
        }
    }

    override fun create(format: Format): SubtitleParser {
        return if (format.isAssSsaFormat()) AssNoOpSubtitleParser else delegate.create(format)
    }
}

@OptIn(UnstableApi::class)
private object AssNoOpSubtitleParser : SubtitleParser {
    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>
    ) = Unit

    override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
}
