package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AssSsaTrackOutputTest {
    @Test
    fun capturesHeaderAndDialogueWhileForwardingSamples() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingAssSampleSink()
        val output = AssSsaTrackOutput(delegate, sink, trackId = 7)
        val header = "[Script Info]\nScriptType: v4.00+".toByteArray()
        val sample = "Dialogue: 0:00:00.00,0:00:02.00,1,0,Default,,0,0,0,,{\\an5}Hi".toByteArray()

        output.format(
            Format.Builder()
                .setSampleMimeType(MimeTypes.TEXT_SSA)
                .setInitializationData(listOf(header))
                .build()
        )
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1_000_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(7, sink.headers.single().trackId)
        assertArrayEquals(header, sink.headers.single().headerData)
        assertEquals(7, sink.samples.single().trackId)
        assertEquals(1_000_000L, sink.samples.single().timeUs)
        assertArrayEquals(sample, sink.samples.single().data)
        assertArrayEquals(sample, delegate.forwardedSample)
    }

    @Test
    fun capturesDataReaderSampleWhileForwardingSameBytes() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingAssSampleSink()
        val output = AssSsaTrackOutput(delegate, sink, trackId = 3)
        val sample = "Dialogue: 0:00:00.00,0:00:01.00,1,0,Default,,0,0,0,,Hi".toByteArray()

        output.format(Format.Builder().setSampleMimeType(MimeTypes.TEXT_SSA).build())
        val bytesRead = output.sampleData(
            ByteArrayDataReader(sample),
            sample.size,
            allowEndOfInput = false,
            TrackOutput.SAMPLE_DATA_PART_MAIN
        )
        output.sampleMetadata(2_000_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertEquals(sample.size, bytesRead)
        assertArrayEquals(sample, delegate.forwardedSample)
        assertArrayEquals(sample, sink.samples.single().data)
    }

    @Test
    fun nonAssTracksAreForwardedAndNotCaptured() {
        val delegate = RecordingTrackOutput()
        val sink = RecordingAssSampleSink()
        val output = AssSsaTrackOutput(delegate, sink, trackId = 4)
        val sample = "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nHi".toByteArray()

        output.format(Format.Builder().setSampleMimeType(MimeTypes.TEXT_VTT).build())
        output.sampleData(ParsableByteArray(sample), sample.size, TrackOutput.SAMPLE_DATA_PART_MAIN)
        output.sampleMetadata(1_000_000L, C.BUFFER_FLAG_KEY_FRAME, sample.size, 0, null)

        assertTrue(sink.headers.isEmpty())
        assertTrue(sink.samples.isEmpty())
        assertArrayEquals(sample, delegate.forwardedSample)
    }

    @Test
    fun missingExtractorOutputReflectionFieldDoesNotThrow() {
        val extractor = AssSsaMatroskaExtractor(
            sink = RecordingAssSampleSink(),
            extractorOutputField = null
        )

        extractor.init(RecordingExtractorOutput())

        extractor.wrapExtractorOutputForTesting()
    }

    private class RecordingAssSampleSink : AssSsaSampleSink {
        val headers = mutableListOf<Header>()
        val samples = mutableListOf<Sample>()
        val fonts = mutableListOf<FontAttachment>()

        override fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format) {
            headers += Header(trackId, headerData, format)
        }

        override fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray) {
            samples += Sample(trackId, timeUs, data)
        }

        override fun onFontAttachment(name: String, data: ByteArray) {
            fonts += FontAttachment(name, data)
        }
    }

    private data class Header(val trackId: Int, val headerData: ByteArray, val format: Format)
    private data class Sample(val trackId: Int, val timeUs: Long, val data: ByteArray)
    private data class FontAttachment(val name: String, val data: ByteArray)

    private class RecordingTrackOutput : TrackOutput {
        private val forwardedSamples = ByteArrayOutputStream()
        val forwardedSample: ByteArray
            get() = forwardedSamples.toByteArray()

        override fun format(format: Format) = Unit

        override fun sampleData(
            input: DataReader,
            length: Int,
            allowEndOfInput: Boolean,
            sampleDataPart: Int
        ): Int {
            val buffer = ByteArray(length)
            val bytesRead = input.read(buffer, 0, length)
            if (bytesRead > 0) {
                forwardedSamples.write(buffer, 0, bytesRead)
            }
            return bytesRead
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            forwardedSamples.write(data.data, data.position, length)
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
