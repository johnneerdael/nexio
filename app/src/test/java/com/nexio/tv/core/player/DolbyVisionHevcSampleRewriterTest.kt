package com.nexio.tv.core.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DolbyVisionHevcSampleRewriterTest {

    @Test
    fun `returns null and never calls converter when sample has only base layer nal units`() {
        var conversionCalls = 0
        val sample = lengthDelimitedSample(
            nal(type = 32, layerId = 0, payload = byteArrayOf(0x10, 0x11)),
            nal(type = 19, layerId = 0, payload = byteArrayOf(0x20, 0x21, 0x22))
        )
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { _, _ ->
                conversionCalls++
                error("ordinary base-layer NALs must not be converted")
            }
        )

        assertNull(rewritten)
        assertEquals(0, conversionCalls)
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(sample.size.toLong(), metrics.inputBytes)
        assertEquals(0L, metrics.outputBytes)
        assertEquals(0L, metrics.sourceCopyBytes)
        assertEquals(0L, metrics.rpuInputBytes)
        assertEquals(0L, metrics.lengthFieldBytes)
        assertEquals(0L, metrics.droppedBytes)
    }

    @Test
    fun `drops enhancement layer nal units and converts only rpu nal units`() {
        val baseVps = nal(type = 32, layerId = 0, payload = byteArrayOf(0x01))
        val enhancementSlice = nal(type = 1, layerId = 1, payload = byteArrayOf(0x02, 0x03, 0x04))
        val rpu = nal(type = 62, layerId = 1, payload = byteArrayOf(0x05, 0x06))
        val baseSlice = nal(type = 19, layerId = 0, payload = byteArrayOf(0x07, 0x08))
        val convertedRpu = nal(type = 62, layerId = 1, payload = byteArrayOf(0x55, 0x66, 0x77))
        val expectedNormalizedRpu = nal(type = 62, layerId = 0, payload = byteArrayOf(0x55, 0x66, 0x77))
        val sample = lengthDelimitedSample(baseVps, enhancementSlice, rpu, baseSlice)
        val expected = lengthDelimitedSample(baseVps, expectedNormalizedRpu, baseSlice)
        val convertedInputs = mutableListOf<ByteArray>()
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { payload, mode ->
                assertEquals(2, mode)
                convertedInputs += payload
                convertedRpu
            }
        )

        assertArrayEquals(expected, rewritten)
        assertEquals(1, convertedInputs.size)
        assertArrayEquals(rpu, convertedInputs.single())
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(sample.size.toLong(), metrics.inputBytes)
        assertEquals(expected.size.toLong(), metrics.outputBytes)
        assertEquals((baseVps.size + baseSlice.size).toLong(), metrics.sourceCopyBytes)
        assertEquals(rpu.size.toLong(), metrics.rpuInputBytes)
        assertEquals(convertedRpu.size.toLong(), metrics.rpuOutputBytes)
        assertEquals((4L * 3L), metrics.lengthFieldBytes)
        assertEquals(enhancementSlice.size.toLong(), metrics.droppedBytes)
    }

    @Test
    fun `converter receives only rpu payloads even when enhancement layer slices exist`() {
        val ordinaryBase = nal(type = 19, layerId = 0, payload = byteArrayOf(0x01, 0x02))
        val enhancement = nal(type = 1, layerId = 1, payload = byteArrayOf(0x03, 0x04))
        val rpuA = nal(type = 62, layerId = 1, payload = byteArrayOf(0x05))
        val rpuB = nal(type = 62, layerId = 0, payload = byteArrayOf(0x06))
        val sample = lengthDelimitedSample(ordinaryBase, enhancement, rpuA, rpuB)
        val seen = mutableListOf<ByteArray>()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { payload, _ ->
                seen += payload
                payload
            }
        )

        val expected = lengthDelimitedSample(
            ordinaryBase,
            nal(type = 62, layerId = 0, payload = byteArrayOf(0x05)),
            rpuB
        )
        assertArrayEquals(expected, rewritten)
        assertEquals(2, seen.size)
        assertArrayEquals(rpuA, seen[0])
        assertArrayEquals(rpuB, seen[1])
    }

    @Test
    fun `returns null for malformed length-delimited sample`() {
        var conversionCalls = 0
        val malformed = byteArrayOf(
            0x00, 0x00, 0x00, 0x10,
            0x40, 0x01, 0x10
        )
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = malformed,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = metrics,
            convertRpu = { _, _ ->
                conversionCalls++
                byteArrayOf()
            }
        )

        assertNull(rewritten)
        assertEquals(0, conversionCalls)
        assertEquals(1L, metrics.sampleCalls)
        assertEquals(malformed.size.toLong(), metrics.inputBytes)
        assertEquals(0L, metrics.outputBytes)
    }

    @Test
    fun `returns null for truncated one byte nal without invoking converter`() {
        var conversionCalls = 0
        val malformed = byteArrayOf(
            0x00, 0x00, 0x00, 0x01,
            0x7C.toByte()
        )

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = malformed,
            nalUnitLengthFieldLength = 4,
            conversionMode = 2,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { _, _ ->
                conversionCalls++
                byteArrayOf(0x01)
            }
        )

        assertNull(rewritten)
        assertEquals(0, conversionCalls)
    }

    @Test
    fun `returns null when rewritten rpu exceeds one byte length field capacity`() {
        var conversionCalls = 0
        val sample = byteArrayOf(0x02) + nal(type = 62, layerId = 1, payload = byteArrayOf())

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 1,
            conversionMode = 2,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { _, _ ->
                conversionCalls++
                ByteArray(256) { 0x11 }
            }
        )

        assertNull(rewritten)
        assertEquals(1, conversionCalls)
    }

    @Test
    fun `accumulates output bytes across successful rewrites`() {
        val metrics = DolbyVisionHevcSampleRewriter.Metrics()
        val sample = lengthDelimitedSample(
            nal(type = 62, layerId = 1, payload = byteArrayOf(0x55, 0x66, 0x77))
        )

        val first = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 5,
            metrics = metrics,
            convertRpu = { payload, _ -> payload }
        ) ?: error("first rewrite should succeed")

        val second = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 5,
            metrics = metrics,
            convertRpu = { payload, _ -> payload }
        ) ?: error("second rewrite should succeed")

        assertEquals(first.size.toLong() + second.size.toLong(), metrics.outputBytes)
    }

    @Test
    fun `normalizes converted rpu layer id to zero`() {
        val rpuLayer63 = byteArrayOf(0x7D.toByte(), 0xF9.toByte(), 0x01)
        assertNalHeader(rpuLayer63, expectedType = 62, expectedLayerId = 63)
        val sample = lengthDelimitedSample(rpuLayer63)

        val rewritten = DolbyVisionHevcSampleRewriter.rewriteLengthDelimitedSample(
            sampleLengthDelimited = sample,
            nalUnitLengthFieldLength = 4,
            conversionMode = 5,
            metrics = DolbyVisionHevcSampleRewriter.Metrics(),
            convertRpu = { payload, mode ->
                assertEquals(5, mode)
                payload
            }
        ) ?: error("RPU layer normalization should rewrite the sample")

        val normalizedRpu = byteArrayOf(0x7C.toByte(), 0x01, 0x01)
        assertNalHeader(normalizedRpu, expectedType = 62, expectedLayerId = 0)
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x00, 0x03,
                0x7C.toByte(), 0x01, 0x01
            ),
            rewritten
        )
    }

    private fun lengthDelimitedSample(vararg nals: ByteArray): ByteArray {
        val total = nals.sumOf { 4 + it.size }
        val out = ByteArray(total)
        var offset = 0
        for (nal in nals) {
            writeLength(out, offset, nal.size)
            offset += 4
            System.arraycopy(nal, 0, out, offset, nal.size)
            offset += nal.size
        }
        return out
    }

    private fun writeLength(out: ByteArray, offset: Int, value: Int) {
        out[offset] = ((value ushr 24) and 0xFF).toByte()
        out[offset + 1] = ((value ushr 16) and 0xFF).toByte()
        out[offset + 2] = ((value ushr 8) and 0xFF).toByte()
        out[offset + 3] = (value and 0xFF).toByte()
    }

    private fun nal(type: Int, layerId: Int, payload: ByteArray): ByteArray {
        require(type in 0..63)
        require(layerId in 0..63)
        val out = ByteArray(2 + payload.size)
        out[0] = ((type shl 1) or ((layerId ushr 5) and 0x01)).toByte()
        out[1] = (((layerId and 0x1F) shl 3) or 0x01).toByte()
        System.arraycopy(payload, 0, out, 2, payload.size)
        return out
    }

    private fun assertNalHeader(nal: ByteArray, expectedType: Int, expectedLayerId: Int) {
        val header = decodeNalHeader(nal)
        assertEquals(expectedType, header.type)
        assertEquals(expectedLayerId, header.layerId)
        assertEquals(1, header.temporalIdPlus1)
    }

    private fun decodeNalHeader(nal: ByteArray): NalHeader {
        require(nal.size >= 2)
        val type = (nal[0].toInt() ushr 1) and 0x3F
        val layerId = ((nal[0].toInt() and 0x01) shl 5) or ((nal[1].toInt() ushr 3) and 0x1F)
        val temporalIdPlus1 = nal[1].toInt() and 0x07
        return NalHeader(type = type, layerId = layerId, temporalIdPlus1 = temporalIdPlus1)
    }

    private data class NalHeader(
        val type: Int,
        val layerId: Int,
        val temporalIdPlus1: Int
    )
}
