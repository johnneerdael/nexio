package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.Consumer
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TappingSubtitleParserFactoryTest {
    @Test
    fun `supportsFormat delegates to wrapped factory`() {
        val delegate = FakeFactory(supports = true)
        val factory = TappingSubtitleParserFactory(delegate, RecordingSink(enabled = true))
        val format = Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build()

        assertTrue(factory.supportsFormat(format))
        assertEquals(1, delegate.supportsCalls)
    }

    @Test
    fun `parse enqueues cue and forwards to media3 output`() {
        val cue = Cue.Builder().setText("hola").build()
        val cues = CuesWithTiming(listOf(cue), 1_000L, 2_000L)
        val delegate = FakeFactory(parser = FakeParser(cues))
        val sink = RecordingSink(enabled = true)
        val parser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
        val forwarded = mutableListOf<CuesWithTiming>()

        parser.parse(byteArrayOf(1, 2), 0, 2, SubtitleParser.OutputOptions.allCues()) {
            forwarded += it
        }

        assertEquals(listOf(cues), forwarded)
        assertEquals(listOf(cues), sink.enqueued.map { it.cues })
    }

    @Test
    fun `disabled sink still forwards to media3 output`() {
        val cues = CuesWithTiming(listOf(Cue.Builder().setText("hola").build()), 1_000L, 2_000L)
        val delegate = FakeFactory(parser = FakeParser(cues))
        val sink = RecordingSink(enabled = false)
        val parser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())
        val forwarded = mutableListOf<CuesWithTiming>()

        parser.parse(byteArrayOf(1), 0, 1, SubtitleParser.OutputOptions.allCues()) {
            forwarded += it
        }

        assertEquals(listOf(cues), forwarded)
        assertTrue(sink.enqueued.isEmpty())
    }

    @Test
    fun `reset notifies sink and delegate parser`() {
        val parser = FakeParser()
        val delegate = FakeFactory(parser = parser)
        val sink = RecordingSink(enabled = true)
        val tappingParser = TappingSubtitleParserFactory(delegate, sink)
            .create(Format.Builder().setSampleMimeType(MimeTypes.APPLICATION_SUBRIP).build())

        tappingParser.reset()

        assertTrue(parser.resetCalled)
        assertEquals(1, sink.resetFormats.size)
    }

    private class RecordingSink(private val enabled: Boolean) : AheadSubtitleCueSink {
        val enqueued = mutableListOf<AheadSubtitleCue>()
        val resetFormats = mutableListOf<Format>()

        override fun isEnabled(format: Format): Boolean = enabled

        override fun enqueue(format: Format, cues: CuesWithTiming) {
            enqueued += AheadSubtitleCue(format, cues)
        }

        override fun onParserReset(format: Format) {
            resetFormats += format
        }
    }

    private class FakeFactory(
        private val supports: Boolean = true,
        private val parser: SubtitleParser = FakeParser()
    ) : SubtitleParser.Factory {
        var supportsCalls = 0

        override fun supportsFormat(format: Format): Boolean {
            supportsCalls += 1
            return supports
        }

        override fun getCueReplacementBehavior(format: Format): Int {
            return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
        }

        override fun create(format: Format): SubtitleParser = parser
    }

    private class FakeParser(private val cues: CuesWithTiming? = null) : SubtitleParser {
        var resetCalled = false

        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>
        ) {
            cues?.let(output::accept)
        }

        override fun reset() {
            resetCalled = true
        }

        override fun getCueReplacementBehavior(): Int = Format.CUE_REPLACEMENT_BEHAVIOR_MERGE
    }
}
