package com.nexio.animemap.binary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class VarintWriterTest {
    @Test
    fun `encodes zero as single byte`() {
        val out = ByteArrayOutputStream()
        val bytes = VarintWriter.writeULong(out, 0L)
        assertEquals(1, bytes)
        assertArrayEquals(byteArrayOf(0x00), out.toByteArray())
    }

    @Test
    fun `encodes 127 as single byte`() {
        val out = ByteArrayOutputStream()
        VarintWriter.writeULong(out, 127L)
        assertArrayEquals(byteArrayOf(0x7F), out.toByteArray())
    }

    @Test
    fun `encodes 128 as two bytes with continuation bit`() {
        val out = ByteArrayOutputStream()
        val bytes = VarintWriter.writeULong(out, 128L)
        assertEquals(2, bytes)
        assertArrayEquals(byteArrayOf(0x80.toByte(), 0x01), out.toByteArray())
    }

    @Test
    fun `encodes max u32-like value`() {
        val out = ByteArrayOutputStream()
        VarintWriter.writeULong(out, 4_294_967_295L)
        // 0xFFFFFFFF -> 5 bytes: FF FF FF FF 0F
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x0F),
            out.toByteArray()
        )
    }

    @Test
    fun `rejects negative values`() {
        val out = ByteArrayOutputStream()
        try {
            VarintWriter.writeULong(out, -1L)
            error("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
