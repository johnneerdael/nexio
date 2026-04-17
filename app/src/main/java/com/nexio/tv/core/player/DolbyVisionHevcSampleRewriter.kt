package com.nexio.tv.core.player

internal object DolbyVisionHevcSampleRewriter {
    private const val NAL_TYPE_UNSPEC62 = 62

    data class Metrics(
        var sampleCalls: Long = 0L,
        var inputBytes: Long = 0L,
        var outputBytes: Long = 0L,
        var sourceCopyBytes: Long = 0L,
        var rpuInputBytes: Long = 0L,
        var rpuOutputBytes: Long = 0L,
        var lengthFieldBytes: Long = 0L,
        var droppedBytes: Long = 0L
    )

    fun rewriteLengthDelimitedSample(
        sampleLengthDelimited: ByteArray,
        nalUnitLengthFieldLength: Int,
        conversionMode: Int,
        metrics: Metrics? = null,
        convertRpu: (payload: ByteArray, mode: Int) -> ByteArray?
    ): ByteArray? {
        if (nalUnitLengthFieldLength !in 1..4) return null

        metrics?.let {
            it.sampleCalls += 1L
            it.inputBytes += sampleLengthDelimited.size.toLong()
        }

        var offset = 0
        var changed = false
        var keptNalBytes = 0L
        var keptLengthFieldBytes = 0L
        var droppedBytes = 0L
        val rpuInputs = ArrayList<RpuInput>()

        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            offset += nalUnitLengthFieldLength
            if (nalSize < 0 || nalSize > sampleLengthDelimited.size - offset) {
                return null
            }

            if (nalSize == 0) {
                keptLengthFieldBytes += nalUnitLengthFieldLength.toLong()
                continue
            }

            if (nalSize < 2) {
                return null
            }

            val nalType = getNalUnitType(sampleLengthDelimited, offset)
            val layerId = getNuhLayerId(sampleLengthDelimited, offset, nalSize)

            if (layerId > 0 && nalType != NAL_TYPE_UNSPEC62) {
                changed = true
                droppedBytes += nalSize.toLong()
                offset += nalSize
                continue
            }

            if (nalType != NAL_TYPE_UNSPEC62) {
                keptLengthFieldBytes += nalUnitLengthFieldLength.toLong()
                keptNalBytes += nalSize.toLong()
                offset += nalSize
                continue
            }

            val payload = ByteArray(nalSize)
            sampleLengthDelimited.copyInto(
                destination = payload,
                destinationOffset = 0,
                startIndex = offset,
                endIndex = offset + nalSize
            )
            rpuInputs += RpuInput(payload = payload)
            offset += nalSize
        }

        if (offset != sampleLengthDelimited.size) return null

        val rpuOutputs = ArrayList<ByteArray>(rpuInputs.size)
        var rpuInputBytes = 0L
        var rpuOutputBytes = 0L
        val maxEncodableRpuSize = maxEncodableNalSize(nalUnitLengthFieldLength)

        for (rpuInput in rpuInputs) {
            val converted = convertRpu(rpuInput.payload, conversionMode)
            val effective = converted?.takeIf { it.isNotEmpty() } ?: rpuInput.payload
            val normalized = normalizeNuhLayerIdToZero(effective)
            if (normalized.size.toLong() > maxEncodableRpuSize) {
                return null
            }
            if (!normalized.contentEquals(rpuInput.payload)) {
                changed = true
            }
            rpuOutputs += normalized
            rpuInputBytes += rpuInput.payload.size.toLong()
            rpuOutputBytes += normalized.size.toLong()
            keptLengthFieldBytes += nalUnitLengthFieldLength.toLong()
        }

        if (!changed) return null

        val outputSize = keptLengthFieldBytes + keptNalBytes + rpuOutputBytes
        if (outputSize <= 0L) return null
        if (outputSize > Int.MAX_VALUE) return null

        val output = ByteArray(outputSize.toInt())
        var outOffset = 0
        var rpuIndex = 0
        offset = 0

        while (offset + nalUnitLengthFieldLength <= sampleLengthDelimited.size) {
            val nalSize = readLengthField(sampleLengthDelimited, offset, nalUnitLengthFieldLength)
            offset += nalUnitLengthFieldLength
            if (nalSize < 0 || nalSize > sampleLengthDelimited.size - offset) {
                return null
            }

            if (nalSize == 0) {
                writeLengthField(output, outOffset, nalUnitLengthFieldLength, nalSize)
                outOffset += nalUnitLengthFieldLength
                continue
            }

            if (nalSize < 2) {
                return null
            }

            val nalType = getNalUnitType(sampleLengthDelimited, offset)
            val layerId = getNuhLayerId(sampleLengthDelimited, offset, nalSize)
            if (layerId > 0 && nalType != NAL_TYPE_UNSPEC62) {
                offset += nalSize
                continue
            }

            if (nalType != NAL_TYPE_UNSPEC62) {
                writeLengthField(output, outOffset, nalUnitLengthFieldLength, nalSize)
                outOffset += nalUnitLengthFieldLength
                sampleLengthDelimited.copyInto(
                    destination = output,
                    destinationOffset = outOffset,
                    startIndex = offset,
                    endIndex = offset + nalSize
                )
                outOffset += nalSize
                offset += nalSize
                continue
            }

            val rewrittenRpu = rpuOutputs[rpuIndex++]
            writeLengthField(output, outOffset, nalUnitLengthFieldLength, rewrittenRpu.size)
            outOffset += nalUnitLengthFieldLength
            rewrittenRpu.copyInto(
                destination = output,
                destinationOffset = outOffset,
                startIndex = 0,
                endIndex = rewrittenRpu.size
            )
            outOffset += rewrittenRpu.size
            offset += nalSize
        }

        if (offset != sampleLengthDelimited.size) return null
        if (rpuIndex != rpuOutputs.size) return null

        metrics?.let {
            it.outputBytes += output.size.toLong()
            it.sourceCopyBytes += keptNalBytes
            it.rpuInputBytes += rpuInputBytes
            it.rpuOutputBytes += rpuOutputBytes
            it.lengthFieldBytes += keptLengthFieldBytes
            it.droppedBytes += droppedBytes
        }

        return output
    }

    private data class RpuInput(
        val payload: ByteArray
    )

    private fun readLengthField(
        sample: ByteArray,
        offset: Int,
        lengthFieldLength: Int
    ): Int {
        var value = 0
        for (index in 0 until lengthFieldLength) {
            value = (value shl 8) or (sample[offset + index].toInt() and 0xFF)
        }
        return value
    }

    private fun writeLengthField(
        out: ByteArray,
        offset: Int,
        lengthFieldLength: Int,
        value: Int
    ) {
        for (index in 0 until lengthFieldLength) {
            val shift = 8 * (lengthFieldLength - 1 - index)
            out[offset + index] = ((value ushr shift) and 0xFF).toByte()
        }
    }

    private fun maxEncodableNalSize(lengthFieldLength: Int): Long {
        return (1L shl (lengthFieldLength * 8)) - 1L
    }

    private fun getNalUnitType(sample: ByteArray, offset: Int): Int {
        return (sample[offset].toInt() ushr 1) and 0x3F
    }

    private fun getNuhLayerId(sample: ByteArray, offset: Int, nalSize: Int): Int {
        if (nalSize < 2) return 0
        val b0 = sample[offset].toInt() and 0x01
        val b1 = sample[offset + 1].toInt() and 0xF8
        return (b0 shl 5) or (b1 ushr 3)
    }

    private fun normalizeNuhLayerIdToZero(nalPayload: ByteArray): ByteArray {
        if (nalPayload.size < 2) return nalPayload
        if (getNuhLayerId(nalPayload, 0, nalPayload.size) == 0) return nalPayload
        val out = nalPayload.copyOf()
        out[0] = (out[0].toInt() and 0xFE).toByte()
        out[1] = (out[1].toInt() and 0x07).toByte()
        return out
    }
}
