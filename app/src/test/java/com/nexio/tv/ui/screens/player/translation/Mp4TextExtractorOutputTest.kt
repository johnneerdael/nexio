package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueEncoder
import java.io.EOFException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Mp4TextExtractorOutputTest {
    @Test
    fun selectedMedia3CueTrackPublishesCueGroup() {
        val published = mutableListOf<PublishedCueGroup>()
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 0 },
            onCueGroup = { cueGroup, format -> published += PublishedCueGroup(cueGroup, format) }
        )
        val track = output.track(7, C.TRACK_TYPE_TEXT)
        val format = media3CueFormat()
        val sample = encodedCueSample("Hello")

        track.format(format)
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(1, published.size)
        assertEquals(1_000L, published.single().cueGroup.presentationTimeUs)
        assertEquals("Hello", published.single().cueGroup.cues.single().text.toString())
        assertEquals(format, published.single().format)
    }

    @Test
    fun unselectedTextTrackIsDiscarded() {
        val published = mutableListOf<PublishedCueGroup>()
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 1 },
            onCueGroup = { cueGroup, format -> published += PublishedCueGroup(cueGroup, format) }
        )
        val track = output.track(7, C.TRACK_TYPE_TEXT)
        val sample = encodedCueSample("Hello")

        track.format(media3CueFormat())
        track.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertTrue(published.isEmpty())
    }

    @Test
    fun selectedMedia3CueTrackPublishesSeparateSamplesWithoutBufferBleed() {
        val published = mutableListOf<CueGroup>()
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 0 },
            onCueGroup = { cueGroup, _ -> published += cueGroup }
        )
        val track = output.track(7, C.TRACK_TYPE_TEXT)
        val first = encodedCueSample("First")
        val second = encodedCueSample("Second")

        track.format(media3CueFormat())
        track.sampleData(ParsableByteArray(first), first.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(10_000L, C.BUFFER_FLAG_KEY_FRAME, first.size, 0, null)
        track.sampleData(ParsableByteArray(second), second.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        track.sampleMetadata(20_000L, C.BUFFER_FLAG_KEY_FRAME, second.size, 0, null)

        assertEquals(
            listOf(10_000L to "First", 20_000L to "Second"),
            published.map { it.presentationTimeUs to it.cues.single().text.toString() }
        )
    }

    @Test
    fun unsupportedTextTrackDoesNotConsumeSupportedOrdinal() {
        val published = mutableListOf<CueGroup>()
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 0 },
            onCueGroup = { cueGroup, _ -> published += cueGroup }
        )
        val unsupportedTrack = output.track(1, C.TRACK_TYPE_TEXT)
        val supportedTrack = output.track(2, C.TRACK_TYPE_TEXT)
        val sample = encodedCueSample("Supported")

        unsupportedTrack.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_VTT)
                .build()
        )
        unsupportedTrack.sampleData(
            ParsableByteArray("WEBVTT".toByteArray()),
            6,
            TrackOutput.SAMPLE_DATA_PART_MAIN
        )
        unsupportedTrack.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, 6, 0, null)
        supportedTrack.format(media3CueFormat())
        supportedTrack.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        supportedTrack.sampleMetadata(2_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(
            listOf(2_000L to "Supported"),
            published.map { it.presentationTimeUs to it.cues.single().text.toString() }
        )
    }

    @Test
    fun repeatedTextTrackIdReturnsSameOutputAndDoesNotConsumeNewOrdinal() {
        val published = mutableListOf<CueGroup>()
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 1 },
            onCueGroup = { cueGroup, _ -> published += cueGroup }
        )
        val firstTrack = output.track(1, C.TRACK_TYPE_TEXT)
        val sameTrack = output.track(1, C.TRACK_TYPE_TEXT)
        val secondTrack = output.track(2, C.TRACK_TYPE_TEXT)
        val firstSample = encodedCueSample("First")
        val secondSample = encodedCueSample("Second")

        assertSame(firstTrack, sameTrack)

        firstTrack.format(media3CueFormat())
        firstTrack.sampleData(ParsableByteArray(firstSample), firstSample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        firstTrack.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, firstSample.size, 0, null)
        secondTrack.format(media3CueFormat())
        secondTrack.sampleData(ParsableByteArray(secondSample), secondSample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        secondTrack.sampleMetadata(2_000L, C.BUFFER_FLAG_KEY_FRAME, secondSample.size, 0, null)

        assertEquals(
            listOf(2_000L to "Second"),
            published.map { it.presentationTimeUs to it.cues.single().text.toString() }
        )
    }

    @Test
    fun dataReaderEndOfInputHonorsAllowEndOfInput() {
        val output = Mp4TextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            selectedSupportedTextTrackOrdinalProvider = { 0 },
            onCueGroup = { _, _ -> }
        )
        val track = output.track(1, C.TRACK_TYPE_TEXT)
        val endOfInputReader = DataReader { _, _, _ -> C.RESULT_END_OF_INPUT }

        assertEquals(
            C.RESULT_END_OF_INPUT,
            track.sampleData(
                endOfInputReader,
                8,
                true,
                TrackOutput.SAMPLE_DATA_PART_MAIN
            )
        )

        try {
            track.sampleData(
                endOfInputReader,
                8,
                false,
                TrackOutput.SAMPLE_DATA_PART_MAIN
            )
            throw AssertionError("Expected EOFException")
        } catch (expected: EOFException) {
            // Expected.
        }
    }

    private fun media3CueFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_MEDIA3_CUES)
            .build()
    }

    private fun encodedCueSample(text: String): ByteArray {
        return CueEncoder().encode(listOf(Cue.Builder().setText(text).build()), 2_000L)
    }

    private data class PublishedCueGroup(
        val cueGroup: CueGroup,
        val format: Format
    )

    private class RecordingExtractorOutput : ExtractorOutput {
        val tracks = mutableListOf<Pair<Int, Int>>()

        override fun track(id: Int, type: Int): TrackOutput {
            tracks += id to type
            return RecordingTrackOutput()
        }

        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }

    private class RecordingTrackOutput : TrackOutput {
        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int = length

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) = Unit
    }
}
