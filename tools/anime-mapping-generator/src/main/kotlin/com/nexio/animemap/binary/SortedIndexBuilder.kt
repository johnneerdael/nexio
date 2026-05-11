package com.nexio.animemap.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface SortedIndexBuilder {
    fun toByteArray(): ByteArray

    /** [u64 key | u32 recordOffset], stride=12 */
    class Single : SortedIndexBuilder {
        private val entries = ArrayList<LongArray>()  // [key, recordOffset]

        fun add(key: Long, recordOffset: Int) {
            entries.add(longArrayOf(key, recordOffset.toLong() and 0xFFFFFFFFL))
        }

        override fun toByteArray(): ByteArray {
            entries.sortBy { it[0] }
            checkNoDuplicates()
            val buf = ByteBuffer.allocate(entries.size * BinaryFormat.STRIDE_U64_SINGLE)
                .order(ByteOrder.LITTLE_ENDIAN)
            for (i in entries.indices) {
                val e = entries[i]
                buf.putLong(e[0])
                buf.putInt(e[1].toInt())
            }
            return buf.array()
        }

        private fun checkNoDuplicates() {
            for (i in 1 until entries.size) {
                check(entries[i][0] != entries[i - 1][0]) {
                    "SortedIndexBuilder.Single: duplicate key ${entries[i][0]}"
                }
            }
        }
    }
}
