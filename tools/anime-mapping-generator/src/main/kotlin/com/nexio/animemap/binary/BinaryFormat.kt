package com.nexio.animemap.binary

internal object BinaryFormat {
    // Header layout
    const val MAGIC: Int = 0x4E584149  // 'N','X','A','I' big-endian — written as bytes below
    val MAGIC_BYTES: ByteArray = byteArrayOf('N'.code.toByte(), 'X'.code.toByte(), 'A'.code.toByte(), 'I'.code.toByte())
    const val SCHEMA_VERSION: Int = 1
    const val HEADER_SIZE: Int = 64
    const val INDEX_DESCRIPTOR_SIZE: Int = 24      // u32 kind | u32 stride | u64 offset | u64 count
    const val INDEX_TABLE_SIZE: Int = INDEX_DESCRIPTOR_SIZE * 9   // 9 slots = 216 bytes

    // Index slot indices (stable, must match runtime reader's IndexKind ordinal)
    const val SLOT_BY_KITSU: Int = 0
    const val SLOT_BY_MAL: Int = 1
    const val SLOT_BY_ANILIST: Int = 2
    const val SLOT_BY_ANIDB: Int = 3
    const val SLOT_BY_TMDB_MOVIE: Int = 4
    const val SLOT_BY_TVDB: Int = 5
    const val SLOT_BY_TMDB_TV: Int = 6
    const val SLOT_BY_IMDB: Int = 7
    const val SLOT_BY_ANIDB_EPISODE: Int = 8

    // Index kind discriminator (in descriptor's first u32)
    const val KIND_U64_SINGLE: Int = 1     // [u64 key | u32 recordOffset] stride=12
    const val KIND_U64_MULTI: Int = 2      // [u64 key | u32 listOff | u32 listLen] stride=16
    const val KIND_IMDB: Int = 3           // [u64 hash | u32 strOff | u32 listOff | u32 listLen] stride=20

    const val STRIDE_U64_SINGLE: Int = 12
    const val STRIDE_U64_MULTI: Int = 16
    const val STRIDE_IMDB: Int = 20

    // Record kinds (first byte of each record)
    const val RECORD_KIND_IDENTITY: Byte = 0
    const val RECORD_KIND_EPISODE: Byte = 1

    // Presence-bit positions for identity records (presenceBits byte)
    const val P_MAL: Int = 1 shl 0
    const val P_ANILIST: Int = 1 shl 1
    const val P_ANIDB: Int = 1 shl 2
    const val P_TMDB: Int = 1 shl 3
    const val P_TVDB: Int = 1 shl 4
    const val P_IMDB: Int = 1 shl 5
    const val P_MEDIA_TYPE: Int = 1 shl 6
    const val P_SOURCE_TYPE: Int = 1 shl 7

    // presenceBits2 byte
    const val P2_TVDB_SEASON: Int = 1 shl 0
    const val P2_TMDB_SEASON: Int = 1 shl 1
    const val P2_TVDB_EP_OFFSET: Int = 1 shl 2
    const val P2_TMDB_EP_OFFSET: Int = 1 shl 3
    const val P2_HAS_MAPPING_RULES: Int = 1 shl 4
    const val P2_HAS_EVIDENCE: Int = 1 shl 5

    // Enum tables
    val MEDIA_TYPE_TABLE: List<String> = listOf("movie", "series", "other")
    val SOURCE_TYPE_TABLE: List<String> = listOf("tv", "ova", "ona", "movie", "music", "special", "other")
    val PROVIDER_TABLE: List<String> = listOf("TVDB", "TMDB")  // for episode ranges/explicit maps targetProvider

    fun mediaTypeByte(value: String?): Byte = when (value?.lowercase()) {
        "movie" -> 0
        "series" -> 1
        else -> 2
    }

    fun sourceTypeByte(value: String?): Byte = when (value?.lowercase()) {
        "tv" -> 0
        "ova" -> 1
        "ona" -> 2
        "movie" -> 3
        "music" -> 4
        "special" -> 5
        else -> 6
    }

    fun providerByte(value: String?): Byte = when (value?.uppercase()) {
        "TVDB" -> 0
        "TMDB" -> 1
        else -> error("unknown targetProvider: $value")
    }
}
