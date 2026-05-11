package com.nexio.tv.core.anime.binary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VarintReaderTest {
    private fun bb(vararg bytes: Int): ByteBuffer {
        val b = ByteBuffer.allocate(bytes.size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.forEach { b.put((it and 0xFF).toByte()) }
        b.flip()
        return b
    }

    @Test
    fun `reads zero`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x00), 0, r)
        assertEquals(0L, r.value); assertEquals(1, next)
    }

    @Test
    fun `reads 127`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x7F), 0, r)
        assertEquals(127L, r.value); assertEquals(1, next)
    }

    @Test
    fun `reads 128 from two bytes`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0x80, 0x01), 0, r)
        assertEquals(128L, r.value); assertEquals(2, next)
    }

    @Test
    fun `reads from non-zero start offset`() {
        val r = LongRef()
        val next = VarintReader.readULong(bb(0xFF, 0x80, 0x01, 0xFF), 1, r)
        assertEquals(128L, r.value); assertEquals(3, next)
    }
}
