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

    @Test
    fun `multi-value index emits sorted entries plus pool of u32 record offsets`() {
        val builder = SortedIndexBuilder.Multi()
        builder.add(key = 200L, recordOffsets = intArrayOf(7, 8, 9))
        builder.add(key = 100L, recordOffsets = intArrayOf(1, 2))
        val (indexBytes, poolBytes) = builder.build()

        // index: 2 entries * 16 bytes = 32
        assertEquals(32, indexBytes.size)
        val ib = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        // entry 0: key=100, listOffset=0, listLen=2
        assertEquals(100L, ib.getLong(0))
        assertEquals(0, ib.getInt(8))
        assertEquals(2, ib.getInt(12))
        // entry 1: key=200, listOffset=8 (after first list of 2 * u32 = 8 bytes), listLen=3
        assertEquals(200L, ib.getLong(16))
        assertEquals(8, ib.getInt(24))
        assertEquals(3, ib.getInt(28))

        // pool: 5 u32 = 20 bytes total
        assertEquals(20, poolBytes.size)
        val pb = ByteBuffer.wrap(poolBytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(1, pb.getInt(0))
        assertEquals(2, pb.getInt(4))
        assertEquals(7, pb.getInt(8))
        assertEquals(8, pb.getInt(12))
        assertEquals(9, pb.getInt(16))
    }

    @Test
    fun `imdb index emits sorted-by-hash entries with stringPool offsets`() {
        val pool = StringPoolBuilder()
        val builder = SortedIndexBuilder.Imdb(pool)
        // Inputs chosen so both FNV-1a hashes are positive when interpreted as
        // signed Long, giving a deterministic h0 < h1 under signed-Long compare:
        //   hash64("tt0286390") = 1_954_078_791_235_011_079
        //   hash64("tt0903747") = 8_173_985_357_356_900_025
        builder.add(imdb = "tt0286390", recordOffsets = intArrayOf(100))
        builder.add(imdb = "tt0903747", recordOffsets = intArrayOf(200, 201))
        val (indexBytes, poolBytes) = builder.build()

        assertEquals(2 * 20, indexBytes.size)
        val ib = ByteBuffer.wrap(indexBytes).order(ByteOrder.LITTLE_ENDIAN)
        // Verify sorted by hash
        val h0 = ib.getLong(0)
        val h1 = ib.getLong(20)
        assert(h0 < h1) { "expected hash-sorted: $h0 < $h1" }
        // Verify each entry's strOffset points at the right utf8 in the pool
        val sp = pool.toByteArray()
        for (i in 0..1) {
            val baseOff = i * 20
            val strOff = ib.getInt(baseOff + 8)
            // pool: [varint len | utf8 ...]
            assertEquals(9L, decodeVarint(sp, strOff))  // both "tt0286390" / "tt0903747" are 9 bytes
        }
        // Pool of record-offsets: 1 + 2 = 3 u32 = 12 bytes
        assertEquals(12, poolBytes.size)
    }

    private fun decodeVarint(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        var shift = 0
        var i = offset
        while (true) {
            val b = bytes[i].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            i++
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }
}
