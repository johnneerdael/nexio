package com.nexio.animemap.binary

/** FNV-1a 64-bit hash. Deterministic, allocation-free, pure JVM. */
object StringHash {
    private const val OFFSET_BASIS: Long = -3750763034362895579L  // 0xCBF29CE484222325
    private const val PRIME: Long = 0x00000100000001B3L

    fun hash64(value: String): Long {
        var h = OFFSET_BASIS
        // UTF-8 bytes are the canonical hash input. For IMDB IDs ("tt0000000")
        // every char is single-byte ASCII so we can use chars directly.
        for (i in value.indices) {
            val b = value[i].code and 0xFF
            h = (h xor b.toLong()) * PRIME
        }
        return h
    }
}
