package com.nexio.animemap.binary

import java.io.OutputStream

object VarintWriter {
    /** Encodes [value] as protobuf-style unsigned LEB128. Returns the byte count written. */
    fun writeULong(out: OutputStream, value: Long): Int {
        require(value >= 0L) { "VarintWriter only encodes non-negative values (got $value)" }
        var remaining = value
        var written = 0
        while (true) {
            val b = (remaining and 0x7FL).toInt()
            remaining = remaining ushr 7
            written++
            if (remaining == 0L) {
                out.write(b)
                return written
            }
            out.write(b or 0x80)
        }
    }
}
