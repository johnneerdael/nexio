package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssNoOpSubtitleParserFactoryTest {
    @Test
    fun supportsAssSsaFormats() {
        val factory = AssNoOpSubtitleParserFactory()

        assertTrue(factory.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()))
    }

    @Test
    fun assParserEmitsNoCuesBecauseAssRendererHandlesTheTrack() {
        val parser = AssNoOpSubtitleParserFactory()
            .create(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        var emitted = false
        val sample = "Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,Hello".toByteArray()

        parser.parse(
            sample,
            0,
            sample.size,
            SubtitleParser.OutputOptions.allCues(),
        ) {
            emitted = true
        }

        assertFalse(emitted)
    }

    @Test
    fun delegatesWebVttToMedia3Parser() {
        val factory = AssNoOpSubtitleParserFactory()
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()

        assertTrue(factory.supportsFormat(format))
        factory.create(format)
    }
}
