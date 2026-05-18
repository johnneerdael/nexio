package com.nexio.tv.ui.screens.player.translation

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.DummyTrackOutput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.CueDecoder
import java.io.ByteArrayOutputStream
import java.io.IOException

internal class Mp4TextExtractorOutput(
    private val delegate: ExtractorOutput,
    private val selectedSupportedTextTrackOrdinalProvider: () -> Int,
    private val onCueGroup: (CueGroup, Format) -> Unit
) : ExtractorOutput {
    private var nextSupportedTextTrackOrdinal = 0

    override fun track(id: Int, type: Int): TrackOutput {
        if (type != C.TRACK_TYPE_TEXT) {
            return delegate.track(id, type)
        }

        val supportedTextTrackOrdinal = nextSupportedTextTrackOrdinal
        nextSupportedTextTrackOrdinal += 1
        return Mp4Media3CueTrackOutput(
            supportedTextTrackOrdinal = supportedTextTrackOrdinal,
            selectedSupportedTextTrackOrdinalProvider = selectedSupportedTextTrackOrdinalProvider,
            onCueGroup = onCueGroup
        )
    }

    override fun endTracks() {
        delegate.endTracks()
    }

    override fun seekMap(seekMap: SeekMap) {
        delegate.seekMap(seekMap)
    }
}

internal object NoOpExtractorOutput : ExtractorOutput {
    override fun track(id: Int, type: Int): TrackOutput = DummyTrackOutput()
    override fun endTracks() = Unit
    override fun seekMap(seekMap: SeekMap) = Unit
}

private class Mp4Media3CueTrackOutput(
    private val supportedTextTrackOrdinal: Int,
    private val selectedSupportedTextTrackOrdinalProvider: () -> Int,
    private val onCueGroup: (CueGroup, Format) -> Unit
) : TrackOutput {
    private val cueDecoder = CueDecoder()
    private val pendingData = ByteArrayOutputStream()
    private var currentFormat: Format? = null

    override fun format(format: Format) {
        currentFormat = format
    }

    @Throws(IOException::class)
    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        val buffer = ByteArray(length)
        val bytesRead = input.read(buffer, 0, length)
        if (bytesRead > 0 && shouldCaptureSampleData()) {
            pendingData.write(buffer, 0, bytesRead)
        }
        return bytesRead
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (shouldCaptureSampleData()) {
            pendingData.write(data.data, data.position, length)
        }
        data.skipBytes(length)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        val format = currentFormat
        if (
            format != null &&
            size > 0 &&
            format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES &&
            supportedTextTrackOrdinal == selectedSupportedTextTrackOrdinalProvider()
        ) {
            publishCueSample(timeUs, size, offset, format)
        }
        retainTrailingBytes(offset)
    }

    private fun publishCueSample(timeUs: Long, size: Int, offset: Int, format: Format) {
        val bufferedBytes = pendingData.toByteArray()
        val sampleEnd = bufferedBytes.size - offset
        val sampleStart = sampleEnd - size
        if (sampleStart < 0 || sampleEnd > bufferedBytes.size || sampleStart > sampleEnd) {
            return
        }

        val cuesWithTiming = cueDecoder.decode(timeUs, bufferedBytes, sampleStart, size)
        if (cuesWithTiming.cues.isEmpty()) {
            return
        }

        onCueGroup(CueGroup(cuesWithTiming.cues, cuesWithTiming.startTimeUs), format)
    }

    private fun retainTrailingBytes(offset: Int) {
        val bufferedBytes = pendingData.toByteArray()
        pendingData.reset()
        if (offset <= 0 || offset > bufferedBytes.size) {
            return
        }

        pendingData.write(bufferedBytes, bufferedBytes.size - offset, offset)
    }

    private fun shouldCaptureSampleData(): Boolean {
        return supportedTextTrackOrdinal == selectedSupportedTextTrackOrdinalProvider()
    }
}
