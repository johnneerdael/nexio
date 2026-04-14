package com.nexio.tv.ui.screens.player.ass

import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException

internal interface AssSsaSampleSink {
    fun onTrackHeader(trackId: Int, headerData: ByteArray, format: Format)
    fun onSubtitleSample(trackId: Int, timeUs: Long, data: ByteArray)
    fun onFontAttachment(name: String, data: ByteArray) = Unit
}

internal class AssSsaTrackOutput(
    private val delegate: TrackOutput,
    private val sink: AssSsaSampleSink,
    private val trackId: Int
) : TrackOutput {
    private val pendingData = ByteArrayOutputStream()
    private var isAssSsaTrack = false

    override fun format(format: Format) {
        isAssSsaTrack = format.isAssSsaFormat()
        if (isAssSsaTrack) {
            format.initializationData
                .filter { it.hasAssSsaHeader() }
                .forEach { sink.onTrackHeader(trackId, it, format) }
        }
        delegate.format(format)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int
    ): Int {
        if (!isAssSsaTrack) {
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
        if (!isAssSsaTrack) {
            delegate.sampleData(data, length, sampleDataPart)
            return
        }

        val sampleData = data.data.copyOfRange(data.position, data.position + length)
        pendingData.write(sampleData)
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?
    ) {
        if (isAssSsaTrack) {
            sink.onSubtitleSample(trackId, timeUs, pendingData.toByteArray())
            pendingData.reset()
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

private fun ByteArray.hasAssSsaHeader(): Boolean {
    return listOf(
        Charsets.UTF_8,
        Charsets.UTF_16LE,
        Charsets.UTF_16BE,
    ).any { charset ->
        runCatching {
            val text = String(this, charset)
            text.contains("[Script Info]", ignoreCase = true) ||
                text.contains("ScriptType:", ignoreCase = true)
        }.getOrDefault(false)
    }
}
