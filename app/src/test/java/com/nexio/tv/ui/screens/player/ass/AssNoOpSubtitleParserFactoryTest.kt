package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AssNoOpSubtitleParserFactoryTest {
    @Test
    fun supportsAssSsaFormats() {
        val factory = AssNoOpSubtitleParserFactory()

        assertTrue(factory.supportsFormat(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build()))
    }

    @Test
    fun embeddedAssParserEmitsNoCuesBecauseAssRendererHandlesTheTrack() {
        val parser = AssNoOpSubtitleParserFactory()
            .create(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.TEXT_SSA)
                    .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
                    .build()
            )
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
    fun sidecarAssParserUsesMedia3CueReplacementBehavior() {
        val factory = AssNoOpSubtitleParserFactory()
        val sidecarFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .build()
        val embeddedFormat = Format.Builder()
            .setSampleMimeType(MimeTypes.TEXT_SSA)
            .setContainerMimeType(MimeTypes.VIDEO_MATROSKA)
            .build()

        assertTrue(factory.supportsFormat(sidecarFormat))
        assertTrue(factory.supportsFormat(embeddedFormat))
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE,
            factory.getCueReplacementBehavior(sidecarFormat)
        )
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE,
            factory.getCueReplacementBehavior(embeddedFormat)
        )
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_MERGE,
            factory.create(sidecarFormat).getCueReplacementBehavior()
        )
        assertEquals(
            Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE,
            factory.create(embeddedFormat).getCueReplacementBehavior()
        )
    }

    @Test
    fun delegatesWebVttToMedia3Parser() {
        val factory = AssNoOpSubtitleParserFactory()
        val format = Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build()

        assertTrue(factory.supportsFormat(format))
        factory.create(format)
    }
}
