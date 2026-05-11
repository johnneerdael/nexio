package com.nexio.animemap.binary

import java.io.OutputStream

object AnimeIdMapBinaryEncoder {

    fun writeIdentityRecord(
        out: OutputStream,
        record: WireAnimeIdMapRecord,
        stringPool: StringPoolBuilder,
    ): Int {
        val buf = java.io.ByteArrayOutputStream()
        // recordKind
        buf.write(BinaryFormat.RECORD_KIND_IDENTITY.toInt())
        // presence bits
        var presence = 0
        if (record.mal != null) presence = presence or BinaryFormat.P_MAL
        if (record.anilist != null) presence = presence or BinaryFormat.P_ANILIST
        if (record.anidb != null) presence = presence or BinaryFormat.P_ANIDB
        if (record.tmdb != null) presence = presence or BinaryFormat.P_TMDB
        if (record.tvdb != null) presence = presence or BinaryFormat.P_TVDB
        if (record.imdb != null) presence = presence or BinaryFormat.P_IMDB
        if (record.mediaType != null) presence = presence or BinaryFormat.P_MEDIA_TYPE
        if (record.sourceType != null) presence = presence or BinaryFormat.P_SOURCE_TYPE
        buf.write(presence)
        var presence2 = 0
        if (record.tvdbSeason != null) presence2 = presence2 or BinaryFormat.P2_TVDB_SEASON
        if (record.tmdbSeason != null) presence2 = presence2 or BinaryFormat.P2_TMDB_SEASON
        if (record.tvdbEpisodeOffset != null) presence2 = presence2 or BinaryFormat.P2_TVDB_EP_OFFSET
        if (record.tmdbEpisodeOffset != null) presence2 = presence2 or BinaryFormat.P2_TMDB_EP_OFFSET
        if (record.hasMappingRules) presence2 = presence2 or BinaryFormat.P2_HAS_MAPPING_RULES
        if (record.evidence.isNotEmpty()) presence2 = presence2 or BinaryFormat.P2_HAS_EVIDENCE
        buf.write(presence2)
        // kitsuId
        VarintWriter.writeULong(buf, record.kitsu.toLong())
        // optional numeric IDs
        record.mal?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.anilist?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.anidb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tmdb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tvdb?.let { VarintWriter.writeULong(buf, it.toLong()) }
        // imdb (string ref into pool)
        record.imdb?.let { writeI32LE(buf, stringPool.intern(it)) }
        // enums
        record.mediaType?.let { buf.write(BinaryFormat.mediaTypeByte(it).toInt() and 0xFF) }
        record.sourceType?.let { buf.write(BinaryFormat.sourceTypeByte(it).toInt() and 0xFF) }
        // season strings (numeric; non-numeric like "a" → encode as 0, hasMappingRules carries the special case flag)
        record.tvdbSeason?.let { VarintWriter.writeULong(buf, parseSeasonAsU64(it)) }
        record.tmdbSeason?.let { VarintWriter.writeULong(buf, parseSeasonAsU64(it)) }
        // signed offsets via zigzag
        record.tvdbEpisodeOffset?.let { VarintWriter.writeULong(buf, zigzag(it)) }
        record.tmdbEpisodeOffset?.let { VarintWriter.writeULong(buf, zigzag(it)) }
        // evidence
        if (record.evidence.isNotEmpty()) {
            VarintWriter.writeULong(buf, record.evidence.size.toLong())
            for (i in record.evidence.indices) {
                writeI32LE(buf, stringPool.intern(record.evidence[i]))
            }
        }
        val bytes = buf.toByteArray()
        out.write(bytes)
        return bytes.size
    }

    /**
     * Parse a tvdbSeason / tmdbSeason value as a numeric u64. Non-numeric
     * values like "a" (absolute-order season per scudlee) round-trip to 0
     * — the reader treats them as "unknown" and the record's `hasMappingRules`
     * flag signals there are explicit episode rules to consult instead.
     */
    private fun parseSeasonAsU64(value: String): Long = value.trim().toLongOrNull() ?: 0L

    private fun zigzag(value: Int): Long = ((value.toLong() shl 1) xor (value.toLong() shr 31))

    private fun writeI32LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 24) and 0xFF)
    }
}
