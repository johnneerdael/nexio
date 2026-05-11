package com.nexio.animemap.binary

import java.io.ByteArrayOutputStream

class StringPoolBuilder {
    private val buffer = ByteArrayOutputStream()
    private val offsetByValue = HashMap<String, Int>()

    /**
     * Returns a u32 byte offset into the eventual pool, or [NULL_OFFSET]
     * if [value] is null or empty.
     */
    fun intern(value: String?): Int {
        if (value.isNullOrEmpty()) return NULL_OFFSET
        offsetByValue[value]?.let { return it }
        val offset = buffer.size()
        val utf8 = value.toByteArray(Charsets.UTF_8)
        VarintWriter.writeULong(buffer, utf8.size.toLong())
        buffer.write(utf8)
        offsetByValue[value] = offset
        return offset
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()

    companion object {
        /** u32 sentinel (0xFFFFFFFF) meaning "no string". */
        const val NULL_OFFSET: Int = -1
    }
}
