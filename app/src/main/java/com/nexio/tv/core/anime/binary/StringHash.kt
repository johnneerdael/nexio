package com.nexio.tv.core.anime.binary

internal object StringHash {
    private const val OFFSET_BASIS: Long = -3750763034362895579L
    private const val PRIME: Long = 0x00000100000001B3L
    fun hash64(value: String): Long {
        var h = OFFSET_BASIS
        for (i in value.indices) {
            val b = value[i].code and 0xFF
            h = (h xor b.toLong()) * PRIME
        }
        return h
    }
}
