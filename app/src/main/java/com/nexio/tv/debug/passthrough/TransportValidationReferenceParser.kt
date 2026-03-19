package com.nexio.tv.debug.passthrough

import java.io.InputStream
import java.security.MessageDigest

object TransportValidationReferenceParser {

    fun parseReferenceBursts(
        sample: TransportValidationSample,
        bytes: ByteArray,
        maxBursts: Int? = null,
    ): List<TransportValidationBurstRecord> {
        if (bytes.size < IEC_PREAMBLE_BYTES) {
            return emptyList()
        }

        val burstSizeBytes = bundledReferenceBurstSize(sample.codecFamily)
        return buildList {
            var offset = 0
            var burstIndex = 0
            while (offset + IEC_PREAMBLE_BYTES <= bytes.size &&
                (maxBursts == null || burstIndex < maxBursts)
            ) {
                val currentBurstSize = minOf(burstSizeBytes, bytes.size - offset)
                if (currentBurstSize < IEC_PREAMBLE_BYTES) {
                    break
                }
                val burstBytes = bytes.copyOfRange(offset, offset + currentBurstSize)
                add(parseBurst(sample, burstBytes, burstIndex, TIME_UNSET))
                offset += currentBurstSize
                burstIndex += 1
            }
        }
    }

    fun parseReferenceBursts(
        sample: TransportValidationSample,
        inputStream: InputStream,
        maxBursts: Int,
    ): List<TransportValidationBurstRecord> {
        if (maxBursts <= 0) {
            return emptyList()
        }
        val burstSizeBytes = bundledReferenceBurstSize(sample.codecFamily)
        val burstBuffer = ByteArray(burstSizeBytes)
        return buildList {
            var burstIndex = 0
            while (burstIndex < maxBursts) {
                val bytesRead = inputStream.readBurstInto(burstBuffer, burstSizeBytes)
                if (bytesRead < IEC_PREAMBLE_BYTES) {
                    break
                }
                val burstBytes = burstBuffer.copyOfRange(0, bytesRead)
                add(parseBurst(sample, burstBytes, burstIndex, TIME_UNSET))
                burstIndex += 1
                if (bytesRead < burstSizeBytes) {
                    break
                }
            }
        }
    }

    fun parseBurst(
        sample: TransportValidationSample,
        bytes: ByteArray,
        burstIndex: Int,
        sourcePtsUs: Long,
        codecFailureHint: TransportValidationFailureCode? = null,
    ): TransportValidationBurstRecord {
        require(bytes.size >= IEC_PREAMBLE_BYTES) {
            "Transport validation bursts must contain at least the IEC preamble"
        }
        val dtsMetadata = parseDtsHdMetadata(sample, bytes)
        return TransportValidationBurstRecord(
            codecFamily = sample.codecFamily,
            sampleId = sample.id,
            burstIndex = burstIndex,
            sourcePtsUs = sourcePtsUs,
            rawBytes = bytes.copyOf(),
            burstSizeBytes = bytes.size,
            pa = littleEndianWord(bytes, 0),
            pb = littleEndianWord(bytes, 2),
            rawPc = littleEndianWord(bytes, 4),
            pd = littleEndianWord(bytes, 6),
            payloadBytes = payloadBytes(sample, bytes, bytes.size),
            zeroPaddingBytes = trailingZeroPaddingBytes(bytes),
            first64ByteHash = sha256Hex(bytes.copyOfRange(0, minOf(64, bytes.size))),
            fullBurstHash = sha256Hex(bytes),
            dtsHdSubtype = dtsMetadata?.subtype,
            dtsHdWrapperPresent = dtsMetadata?.wrapperPresent,
            dtsHdWrapperPayloadSize = dtsMetadata?.wrapperPayloadSize,
            dtsHdPayloadClassification = dtsMetadata?.payloadClassification,
            codecFailureHint = codecFailureHint,
        )
    }

    private fun bundledReferenceBurstSize(codecFamily: TransportValidationCodecFamily): Int =
        when (codecFamily) {
            TransportValidationCodecFamily.AC3 -> 6_144
            TransportValidationCodecFamily.E_AC3,
            TransportValidationCodecFamily.E_AC3_JOC -> 24_576
            TransportValidationCodecFamily.DTS_CORE -> 2_048
            TransportValidationCodecFamily.DTS_HD,
            TransportValidationCodecFamily.DTS_X -> 16_384
            TransportValidationCodecFamily.TRUEHD -> 61_440
        }

    private fun payloadBytes(
        sample: TransportValidationSample,
        burstBytes: ByteArray,
        burstSizeBytes: Int,
    ): Int {
        val pd = littleEndianWord(burstBytes, 6)
        return when (sample.pdRule) {
            TransportValidationPdRule.TRUEHD_MAT -> pd
            else -> minOf(pd ushr 3, burstSizeBytes - IEC_PREAMBLE_BYTES)
        }
    }

    private fun trailingZeroPaddingBytes(bytes: ByteArray): Int {
        var index = bytes.lastIndex
        while (index >= IEC_PREAMBLE_BYTES && bytes[index].toInt() == 0) {
            index -= 1
        }
        return bytes.lastIndex - index
    }

    private fun parseDtsHdMetadata(
        sample: TransportValidationSample,
        bytes: ByteArray,
    ): DtsHdMetadata? {
        if (sample.codecFamily != TransportValidationCodecFamily.DTS_HD &&
            sample.codecFamily != TransportValidationCodecFamily.DTS_X
        ) {
            return null
        }
        val wrapperPresent =
            bytes.size >= DTS_HD_WRAPPER_OFFSET + DTS_HD_WRAPPER_START_CODES.first().size + 2 &&
                DTS_HD_WRAPPER_START_CODES.any { startCode ->
                    bytes.copyOfRange(
                        DTS_HD_WRAPPER_OFFSET,
                        DTS_HD_WRAPPER_OFFSET + startCode.size
                    ).contentEquals(startCode)
                }
        val payloadSize =
            if (wrapperPresent) {
                bigEndianWord(bytes, DTS_HD_WRAPPER_OFFSET + DTS_HD_WRAPPER_START_CODES.first().size)
            } else {
                null
            }
        return DtsHdMetadata(
            subtype = littleEndianWord(bytes, 4) ushr 8,
            wrapperPresent = wrapperPresent,
            wrapperPayloadSize = payloadSize,
            payloadClassification = classifyDtsHdPayload(
                normalizedPc = littleEndianWord(bytes, 4) and 0x7F,
                wrapperPresent = wrapperPresent,
                wrapperPayloadSize = payloadSize,
            ),
        )
    }

    private fun classifyDtsHdPayload(
        normalizedPc: Int,
        wrapperPresent: Boolean,
        wrapperPayloadSize: Int?,
    ): TransportValidationDtsPayloadClassification? {
        if (normalizedPc != DTS_HD_TYPE_IV_PC || !wrapperPresent || wrapperPayloadSize == null) {
            return null
        }
        return when {
            wrapperPayloadSize <= DTS_CORE_LIKELY_MAX_WRAPPED_PAYLOAD_BYTES -> {
                TransportValidationDtsPayloadClassification.LIKELY_CORE_ONLY_PAYLOAD
            }

            wrapperPayloadSize >= DTS_HD_LIKELY_MIN_WRAPPED_PAYLOAD_BYTES -> {
                TransportValidationDtsPayloadClassification.HD_PAYLOAD
            }

            else -> TransportValidationDtsPayloadClassification.UNKNOWN_PAYLOAD
        }
    }

    private fun littleEndianWord(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun bigEndianWord(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    private fun InputStream.readBurstInto(
        buffer: ByteArray,
        burstSizeBytes: Int,
    ): Int {
        var totalRead = 0
        while (totalRead < burstSizeBytes) {
            val read = read(buffer, totalRead, burstSizeBytes - totalRead)
            if (read <= 0) {
                break
            }
            totalRead += read
        }
        return totalRead
    }

    private const val IEC_PREAMBLE_BYTES = 8
    private const val TIME_UNSET = Long.MIN_VALUE + 1
    private const val DTS_HD_TYPE_IV_PC = 0x11
    private const val DTS_HD_WRAPPER_OFFSET = 8
    // FFmpeg's DTS-HD transport fallback keeps the type-IV wrapper but collapses pkt_size toward
    // a core-sized payload. Our bundled DTS-HD/DTS:X references carry wrapper payload sizes near
    // 19-20 KB, so keep a conservative unknown band between obvious core-sized and HD-sized paths.
    private const val DTS_CORE_LIKELY_MAX_WRAPPED_PAYLOAD_BYTES = 4_096
    private const val DTS_HD_LIKELY_MIN_WRAPPED_PAYLOAD_BYTES = 8_192
    private val DTS_HD_WRAPPER_START_CODES =
        listOf(
            byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFE.toByte(), 0xFE.toByte()),
            byteArrayOf(0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFE.toByte(), 0xFE.toByte())
        )

    private data class DtsHdMetadata(
        val subtype: Int,
        val wrapperPresent: Boolean,
        val wrapperPayloadSize: Int?,
        val payloadClassification: TransportValidationDtsPayloadClassification?,
    )
}
