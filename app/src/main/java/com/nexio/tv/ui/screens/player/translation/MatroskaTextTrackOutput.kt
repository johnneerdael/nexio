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
import java.io.EOFException
import java.util.Locale

internal data class HarvestedMatroskaTextSample(
    val trackId: Int,
    val supportedTrackOrdinal: Int,
    val timeUs: Long,
    val text: String,
    val format: Format
)

internal interface MatroskaTextTrackSink {
    fun onSupportedTextTrack(trackId: Int, supportedTrackOrdinal: Int, format: Format)
    fun onSubtitleSample(sample: HarvestedMatroskaTextSample)
}

internal class MatroskaTextExtractorOutput(
    private val delegate: ExtractorOutput,
    private val sink: MatroskaTextTrackSink,
    private val selectedSupportedTrackOrdinalProvider: () -> Int
) : ExtractorOutput {
    private val supportedTrackOrdinalAllocator = OrdinalAllocator()

    override fun track(id: Int, type: Int): TrackOutput {
        return MatroskaTextTrackOutput(
            delegate = delegate.track(id, type),
            sink = sink,
            trackId = id,
            selectedSupportedTrackOrdinalProvider = selectedSupportedTrackOrdinalProvider,
            supportedTrackOrdinalAllocator = supportedTrackOrdinalAllocator
        )
    }

    override fun endTracks() {
        delegate.endTracks()
    }

    override fun seekMap(seekMap: SeekMap) {
        delegate.seekMap(seekMap)
    }
}

internal class MatroskaTextTrackOutput(
    private val delegate: TrackOutput,
    private val sink: MatroskaTextTrackSink,
    private val trackId: Int,
    private val selectedSupportedTrackOrdinalProvider: () -> Int,
    private val supportedTrackOrdinalAllocator: OrdinalAllocator = OrdinalAllocator()
) : TrackOutput {
    private val pendingData = ByteArrayOutputStream()
    private var supportedTrackOrdinal: Int? = null
    private var supportedFormat: Format? = null

    override fun format(format: Format) {
        if (format.isSubRipTextFormat() && supportedTrackOrdinal == null) {
            val ordinal = supportedTrackOrdinalAllocator.nextOrdinal()
            supportedTrackOrdinal = ordinal
            supportedFormat = format
            sink.onSupportedTextTrack(trackId, ordinal, format)
        } else if (supportedTrackOrdinal != null) {
            supportedFormat = format
        }
        delegate.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!shouldCaptureSampleData()) {
            return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
        }

        val buffer = ByteArray(length)
        val bytesRead = input.read(buffer, 0, length)
        if (bytesRead < 0) {
            if (allowEndOfInput) return C.RESULT_END_OF_INPUT
            throw EOFException()
        }
        if (bytesRead > 0) {
            pendingData.write(buffer, 0, bytesRead)
            delegate.sampleData(
                ParsableByteArray(buffer.copyOf(bytesRead)),
                bytesRead,
                sampleDataPart
            )
        }
        return bytesRead
    }

    override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
        if (shouldCaptureSampleData()) {
            pendingData.write(data.data, data.position, length)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        val ordinal = supportedTrackOrdinal
        val format = supportedFormat
        if (
            ordinal != null &&
            format != null &&
            ordinal == selectedSupportedTrackOrdinalProvider()
        ) {
            val text = pendingData.toByteArray().toString(Charsets.UTF_8).trim()
            if (text.isNotBlank()) {
                sink.onSubtitleSample(
                    HarvestedMatroskaTextSample(
                        trackId = trackId,
                        supportedTrackOrdinal = ordinal,
                        timeUs = timeUs,
                        text = text,
                        format = format
                    )
                )
            }
        }
        if (ordinal != null) {
            pendingData.reset()
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    private fun shouldCaptureSampleData(): Boolean {
        val ordinal = supportedTrackOrdinal ?: return false
        return ordinal == selectedSupportedTrackOrdinalProvider()
    }
}

internal class OrdinalAllocator {
    private var nextOrdinal = 0

    fun nextOrdinal(): Int {
        val ordinal = nextOrdinal
        nextOrdinal += 1
        return ordinal
    }
}

private fun Format.isSubRipTextFormat(): Boolean {
    val mimeType = sampleMimeType?.trim()?.lowercase(Locale.ROOT)
    return mimeType == MimeTypes.APPLICATION_SUBRIP || mimeType == "application/x-subrip"
}
