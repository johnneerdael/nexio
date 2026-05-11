package com.nexio.animemap.binary

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SortedIndexBuilderTest {
    @Test
    fun `single-value index emits sorted fixed-stride entries`() {
        val builder = SortedIndexBuilder.Single()
        builder.add(key = 300L, recordOffset = 50)
        builder.add(key = 100L, recordOffset = 10)
        builder.add(key = 200L, recordOffset = 20)
        val bytes = builder.toByteArray()

        // 3 entries * 12 bytes = 36 bytes total
        assertEquals(36, bytes.size)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        // Sorted ascending by key: 100, 200, 300
        assertEquals(100L, buf.getLong(0))
        assertEquals(10, buf.getInt(8))
        assertEquals(200L, buf.getLong(12))
        assertEquals(20, buf.getInt(20))
        assertEquals(300L, buf.getLong(24))
        assertEquals(50, buf.getInt(32))
    }

    @Test
    fun `single-value index rejects duplicate keys`() {
        val builder = SortedIndexBuilder.Single()
        builder.add(key = 42L, recordOffset = 1)
        try {
            builder.add(key = 42L, recordOffset = 2)
            builder.toByteArray()
            error("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
