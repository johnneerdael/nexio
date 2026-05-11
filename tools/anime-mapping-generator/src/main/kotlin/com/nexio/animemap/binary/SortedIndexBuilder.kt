package com.nexio.animemap.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder

sealed interface SortedIndexBuilder {

    /** [u64 key | u32 recordOffset], stride=12 */
    class Single : SortedIndexBuilder {
        private val entries = ArrayList<LongArray>()  // [key, recordOffset]

        fun add(key: Long, recordOffset: Int) {
            entries.add(longArrayOf(key, recordOffset.toLong() and 0xFFFFFFFFL))
        }

        fun toByteArray(): ByteArray {
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

    /** [u64 key | u32 listOffset | u32 listLen], stride=16, plus shared u32 pool. */
    class Multi : SortedIndexBuilder {
        private val entries = ArrayList<Pair<Long, IntArray>>()

        fun add(key: Long, recordOffsets: IntArray) {
            entries.add(key to recordOffsets)
        }

        fun build(): Pair<ByteArray, ByteArray> {
            entries.sortBy { it.first }
            for (i in 1 until entries.size) {
                check(entries[i].first != entries[i - 1].first) {
                    "SortedIndexBuilder.Multi: duplicate key ${entries[i].first}"
                }
            }
            val indexBuf = ByteBuffer.allocate(entries.size * BinaryFormat.STRIDE_U64_MULTI)
                .order(ByteOrder.LITTLE_ENDIAN)
            val poolBuf = ByteBuffer.allocate(entries.sumOf { it.second.size } * 4)
                .order(ByteOrder.LITTLE_ENDIAN)
            var poolOffset = 0
            for (i in entries.indices) {
                val (key, offsets) = entries[i]
                indexBuf.putLong(key)
                indexBuf.putInt(poolOffset)
                indexBuf.putInt(offsets.size)
                for (j in offsets.indices) poolBuf.putInt(offsets[j])
                poolOffset += offsets.size * 4
            }
            return indexBuf.array() to poolBuf.array()
        }
    }
}
