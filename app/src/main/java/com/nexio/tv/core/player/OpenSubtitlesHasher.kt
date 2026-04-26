package com.nexio.tv.core.player

/**
 * Calculates the OpenSubtitles file hash for a video stream.
 * Algorithm: hash = fileSize + sum of first 64KB longs + sum of last 64KB longs (little-endian)
 * https://trac.opensubtitles.org/projects/opensubtitles/wiki/HashSourceCodes
 */
object OpenSubtitlesHasher {

    const val CHUNK_SIZE = 65536L // 64 KB
    private const val LONG_SIZE = 8

    data class Result(val hash: String, val fileSize: Long)

    fun computeFromChunks(fileSize: Long, firstChunk: ByteArray, lastChunk: ByteArray): Result? {
        if (fileSize < CHUNK_SIZE * 2) return null
        if (firstChunk.size < CHUNK_SIZE.toInt() || lastChunk.size < CHUNK_SIZE.toInt()) {
            return null
        }
        var hash = fileSize
        hash += firstChunk.sumLittleEndianLongs()
        hash += lastChunk.sumLittleEndianLongs()
        return Result(hash = "%016x".format(hash), fileSize = fileSize)
    }

    private fun ByteArray.sumLittleEndianLongs(): Long {
        var sum = 0L
        var offset = 0
        while (offset + LONG_SIZE <= size) {
            var value = 0L
            for (index in 0 until LONG_SIZE) {
                value = value or ((this[offset + index].toLong() and 0xFF) shl (index * 8))
            }
            sum += value
            offset += LONG_SIZE
        }
        return sum
    }
}
