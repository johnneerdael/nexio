package com.nexio.tv.core.anime.binary

import java.nio.ByteBuffer

/** Reusable holder so callers can avoid boxing the return value. */
internal class LongRef { var value: Long = 0L }

internal object VarintReader {
    /**
     * Read an unsigned LEB128 varint from [buf] starting at absolute [offset].
     * Stores the decoded value in [into] and returns the absolute byte
     * offset just past the last byte read.
     */
    fun readULong(buf: ByteBuffer, offset: Int, into: LongRef): Int {
        var result = 0L
        var shift = 0
        var i = offset
        while (true) {
            val b = buf.get(i).toInt() and 0xFF
            i++
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                into.value = result
                return i
            }
            shift += 7
            check(shift < 64) { "varint overflow at offset $offset" }
        }
    }

    /** Zigzag-decoded signed int. */
    fun readSInt(buf: ByteBuffer, offset: Int, into: LongRef): Int {
        val next = readULong(buf, offset, into)
        val raw = into.value
        val v = ((raw ushr 1).toInt()) xor (-(raw.toInt() and 1))
        into.value = v.toLong()
        return next
    }
}
