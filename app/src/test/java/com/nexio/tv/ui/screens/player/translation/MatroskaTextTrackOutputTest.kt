package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaTextTrackOutputTest {
    @Test
    fun selectedSubripTrackCapturesSamples() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingMatroskaTextTrackSink()
        val output = MatroskaTextTrackOutput(
            delegate = delegate,
            sink = sink,
            trackId = 7,
            supportedTrackOrdinalAllocator = OrdinalAllocator(),
            selectedSupportedTrackOrdinalProvider = { 0 }
        )
        val format = subripFormat()
        val sample = "hello".toByteArray()

        output.format(format)
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(format, delegate.formats.single())
        assertEquals(7, sink.supportedTracks.single().trackId)
        assertEquals(0, sink.supportedTracks.single().supportedTrackOrdinal)
        assertEquals(format, sink.supportedTracks.single().format)
        assertEquals(
            HarvestedMatroskaTextSample(
                trackId = 7,
                supportedTrackOrdinal = 0,
                timeUs = 1_000L,
                text = "hello",
                format = format
            ),
            sink.samples.single()
        )
    }

    @Test
    fun nonSelectedSubripTrackIsIgnored() {
        val sink = RecordingMatroskaTextTrackSink()
        val output = MatroskaTextTrackOutput(
            delegate = RecordingTrackOutput(),
            sink = sink,
            trackId = 7,
            supportedTrackOrdinalAllocator = OrdinalAllocator(),
            selectedSupportedTrackOrdinalProvider = { 1 }
        )
        val sample = "hello".toByteArray()

        output.format(subripFormat())
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertTrue(sink.samples.isEmpty())
    }

    @Test
    fun delegateReceivesSampleDataAndMetadata() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingMatroskaTextTrackSink()
        val output = MatroskaTextTrackOutput(
            delegate = delegate,
            sink = sink,
            trackId = 3,
            supportedTrackOrdinalAllocator = OrdinalAllocator(),
            selectedSupportedTrackOrdinalProvider = { 0 }
        )
        val sample = "delegate me".toByteArray()

        output.format(subripFormat())
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(2_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 4, null)

        assertArrayEquals(sample, delegate.forwardedSample)
        assertEquals(1, delegate.metadata.size)
        assertEquals(2_000L, delegate.metadata.single().timeUs)
        assertEquals(sample.size, delegate.metadata.single().size)
        assertEquals(4, delegate.metadata.single().offset)
    }

    @Test
    fun dataReaderSampleCapturesAndForwardsSameBytes() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingMatroskaTextTrackSink()
        val output = MatroskaTextTrackOutput(
            delegate = delegate,
            sink = sink,
            trackId = 9,
            supportedTrackOrdinalAllocator = OrdinalAllocator(),
            selectedSupportedTrackOrdinalProvider = { 0 }
        )
        val sample = "from reader".toByteArray()

        output.format(subripFormat())
        val bytesRead = output.sampleData(
            ByteArrayDataReader(sample),
            sample.size,
            allowEndOfInput = false,
            TrackOutput.SAMPLE_DATA_PART_MAIN
        )
        output.sampleMetadata(3_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(sample.size, bytesRead)
        assertEquals(1, delegate.dataReaderSampleDataCalls)
        assertEquals(0, delegate.parsableByteArraySampleDataCalls)
        assertArrayEquals(sample, delegate.forwardedSample)
        assertEquals("from reader", sink.samples.single().text)
    }

    @Test
    fun blankTextIsIgnored() {
        val sink = RecordingMatroskaTextTrackSink()
        val output = MatroskaTextTrackOutput(
            delegate = RecordingTrackOutput(),
            sink = sink,
            trackId = 4,
            supportedTrackOrdinalAllocator = OrdinalAllocator(),
            selectedSupportedTrackOrdinalProvider = { 0 }
        )
        val sample = " \n\t ".toByteArray()

        output.format(subripFormat())
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(4_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertTrue(sink.samples.isEmpty())
    }

    @Test
    fun extractorOutputScopesOrdinalCounterPerInstance() {
        val firstSink = RecordingMatroskaTextTrackSink()
        val secondSink = RecordingMatroskaTextTrackSink()
        val first = MatroskaTextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            sink = firstSink,
            selectedSupportedTrackOrdinalProvider = { 0 }
        )
        val second = MatroskaTextExtractorOutput(
            delegate = RecordingExtractorOutput(),
            sink = secondSink,
            selectedSupportedTrackOrdinalProvider = { 0 }
        )

        first.track(1, C.TRACK_TYPE_TEXT).format(subripFormat())
        second.track(2, C.TRACK_TYPE_TEXT).format(subripFormat())

        assertEquals(0, firstSink.supportedTracks.single().supportedTrackOrdinal)
        assertEquals(0, secondSink.supportedTracks.single().supportedTrackOrdinal)
    }

    private fun subripFormat(): Format {
        return Format.Builder()
            .setSampleMimeType(MimeTypes.APPLICATION_SUBRIP)
            .build()
    }

    private class RecordingMatroskaTextTrackSink : MatroskaTextTrackSink {
        val supportedTracks = mutableListOf<SupportedTrack>()
        val samples = mutableListOf<HarvestedMatroskaTextSample>()

        override fun onSupportedTextTrack(
            trackId: Int,
            supportedTrackOrdinal: Int,
            format: Format
        ) {
            supportedTracks += SupportedTrack(trackId, supportedTrackOrdinal, format)
        }

        override fun onSubtitleSample(sample: HarvestedMatroskaTextSample) {
            samples += sample
        }
    }

    private data class SupportedTrack(
        val trackId: Int,
        val supportedTrackOrdinal: Int,
        val format: Format
    )

    private class RecordingTrackOutput : TrackOutput {
        private val forwardedSamples = ByteArrayOutputStream()
        val forwardedSample: ByteArray
            get() = forwardedSamples.toByteArray()
        val formats = mutableListOf<Format>()
        val metadata = mutableListOf<Metadata>()
        var dataReaderSampleDataCalls = 0
            private set
        var parsableByteArraySampleDataCalls = 0
            private set

        override fun format(format: Format) {
            formats += format
        }

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            dataReaderSampleDataCalls += 1
            val buffer = ByteArray(length)
            val bytesRead = input.read(buffer, 0, length)
            if (bytesRead > 0) {
                forwardedSamples.write(buffer, 0, bytesRead)
            }
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            parsableByteArraySampleDataCalls += 1
            forwardedSamples.write(data.data, data.position, length)
            data.skipBytes(length)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?
        ) {
            metadata += Metadata(timeUs, flags, size, offset, cryptoData)
        }
    }

    private data class Metadata(
        val timeUs: Long,
        val flags: Int,
        val size: Int,
        val offset: Int,
        val cryptoData: TrackOutput.CryptoData?
    )

    private class ByteArrayDataReader(private val data: ByteArray) : DataReader {
        private var position = 0

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (position == data.size) return C.RESULT_END_OF_INPUT
            val bytesToRead = minOf(length, data.size - position)
            data.copyInto(buffer, offset, position, position + bytesToRead)
            position += bytesToRead
            return bytesToRead
        }
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput = RecordingTrackOutput()
        override fun endTracks() = Unit
        override fun seekMap(seekMap: SeekMap) = Unit
    }
}
