package com.nexio.tv.core.anime.binary

internal object BinaryFormat {
    val MAGIC_BYTES: ByteArray = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte())
    const val SCHEMA_VERSION: Int = 1
    const val HEADER_SIZE: Int = 64
    const val INDEX_DESCRIPTOR_SIZE: Int = 24
    const val INDEX_TABLE_SIZE: Int = INDEX_DESCRIPTOR_SIZE * 9

    const val KIND_U64_SINGLE: Int = 1
    const val KIND_U64_MULTI: Int = 2
    const val KIND_IMDB: Int = 3

    const val STRIDE_U64_SINGLE: Int = 12
    const val STRIDE_U64_MULTI: Int = 16
    const val STRIDE_IMDB: Int = 20

    const val RECORD_KIND_IDENTITY: Byte = 0
    const val RECORD_KIND_EPISODE: Byte = 1

    const val P_MAL: Int = 1 shl 0
    const val P_ANILIST: Int = 1 shl 1
    const val P_ANIDB: Int = 1 shl 2
    const val P_TMDB: Int = 1 shl 3
    const val P_TVDB: Int = 1 shl 4
    const val P_IMDB: Int = 1 shl 5
    const val P_MEDIA_TYPE: Int = 1 shl 6
    const val P_SOURCE_TYPE: Int = 1 shl 7

    const val P2_TVDB_SEASON: Int = 1 shl 0
    const val P2_TMDB_SEASON: Int = 1 shl 1
    const val P2_TVDB_EP_OFFSET: Int = 1 shl 2
    const val P2_TMDB_EP_OFFSET: Int = 1 shl 3
    const val P2_HAS_MAPPING_RULES: Int = 1 shl 4
    const val P2_HAS_EVIDENCE: Int = 1 shl 5

    val MEDIA_TYPE_TABLE: List<String> = listOf("movie", "series", "other")
    val SOURCE_TYPE_TABLE: List<String> = listOf("tv", "ova", "ona", "movie", "music", "special", "other")

    const val NULL_STRING_OFFSET: Int = -1
}
