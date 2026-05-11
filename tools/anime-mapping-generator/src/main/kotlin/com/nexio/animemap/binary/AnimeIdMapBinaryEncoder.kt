package com.nexio.animemap.binary

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

    fun writeEpisodeRecord(
        out: OutputStream,
        record: WireAnimeEpisodeMappingRecord,
        stringPool: StringPoolBuilder,
    ): Int {
        val buf = java.io.ByteArrayOutputStream()
        buf.write(BinaryFormat.RECORD_KIND_EPISODE.toInt())
        var presence = 0
        if (record.name != null) presence = presence or 0x01
        if (record.tvdbSeriesId != null) presence = presence or 0x02
        if (record.tmdbTvId != null) presence = presence or 0x04
        if (record.evidence.isNotEmpty()) presence = presence or 0x08
        buf.write(presence)
        VarintWriter.writeULong(buf, record.anidb.toLong())
        record.name?.let { writeI32LE(buf, stringPool.intern(it)) }
        record.tvdbSeriesId?.let { VarintWriter.writeULong(buf, it.toLong()) }
        record.tmdbTvId?.let { VarintWriter.writeULong(buf, it.toLong()) }
        VarintWriter.writeULong(buf, record.ranges.size.toLong())
        for (i in record.ranges.indices) writeRange(buf, record.ranges[i])
        VarintWriter.writeULong(buf, record.explicitMaps.size.toLong())
        for (i in record.explicitMaps.indices) writeExplicit(buf, record.explicitMaps[i])
        if (record.evidence.isNotEmpty()) {
            VarintWriter.writeULong(buf, record.evidence.size.toLong())
            for (i in record.evidence.indices) writeI32LE(buf, stringPool.intern(record.evidence[i]))
        }
        val bytes = buf.toByteArray()
        out.write(bytes)
        return bytes.size
    }

    private fun writeRange(out: OutputStream, range: WireAnimeRangeRule) {
        // 11 bytes fixed: u8 srcSeason | u16 startEp | u16 endEp | u8 prov | u8 tgtSeason | i16 offset | u8 hasEndEp
        out.write(range.sourceSeason.coerceIn(0, 255))
        writeU16LE(out, range.startEpisode)
        writeU16LE(out, range.endEpisode ?: 0)
        out.write(BinaryFormat.providerByte(range.targetProvider).toInt() and 0xFF)
        out.write(range.targetSeason.coerceIn(0, 255))
        writeI16LE(out, range.offset.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()))
        out.write(if (range.endEpisode != null) 1 else 0)
    }

    private fun writeExplicit(out: OutputStream, map: WireAnimeExplicitMap) {
        // 7 bytes fixed
        out.write(map.sourceSeason.coerceIn(0, 255))
        writeU16LE(out, map.sourceEpisode)
        out.write(BinaryFormat.providerByte(map.targetProvider).toInt() and 0xFF)
        out.write(map.targetSeason.coerceIn(0, 255))
        writeU16LE(out, map.targetEpisode)
    }

    private fun writeU16LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
    }
    private fun writeI16LE(out: OutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value ushr 8) and 0xFF)
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

    fun encode(asset: WireAnimeIdMapAsset): ByteArray {
        val stringPool = StringPoolBuilder()
        val recordsBuf = java.io.ByteArrayOutputStream()
        // recordOffset is bytes-from-start-of-records-region
        val identityRecordOffsets = HashMap<String, Int>(asset.identityRecords.size * 2)
        for (rec in asset.identityRecords) {
            val offset = recordsBuf.size()
            writeIdentityRecord(recordsBuf, rec, stringPool)
            identityRecordOffsets[rec.kitsu] = offset
        }
        val episodeRecordOffsets = HashMap<String, Int>(asset.episodeMappings.size * 2)
        for (ep in asset.episodeMappings) {
            val offset = recordsBuf.size()
            writeEpisodeRecord(recordsBuf, ep, stringPool)
            episodeRecordOffsets[ep.anidb] = offset
        }
        val recordsBytes = recordsBuf.toByteArray()

        // Build indexes
        fun singleFromMap(map: Map<String, String>): ByteArray {
            val b = SortedIndexBuilder.Single()
            for ((k, v) in map) {
                val recOff = identityRecordOffsets[v]
                    ?: error("byKitsu/byMal/... references missing record kitsu=$v")
                b.add(k.toLong(), recOff)
            }
            return b.toByteArray()
        }
        fun multiFromMap(map: Map<String, List<String>>): Pair<ByteArray, ByteArray> {
            val b = SortedIndexBuilder.Multi()
            for ((k, vs) in map) {
                val offsets = IntArray(vs.size) {
                    identityRecordOffsets[vs[it]]
                        ?: error("multi-value index references missing record kitsu=${vs[it]}")
                }
                b.add(k.toLong(), offsets)
            }
            return b.build()
        }
        fun imdbFromMap(map: Map<String, List<String>>): Pair<ByteArray, ByteArray> {
            val b = SortedIndexBuilder.Imdb(stringPool)
            for ((k, vs) in map) {
                val offsets = IntArray(vs.size) {
                    identityRecordOffsets[vs[it]] ?: error("byImdb references missing record kitsu=${vs[it]}")
                }
                b.add(k, offsets)
            }
            return b.build()
        }

        val byKitsuBytes = singleFromMap(asset.byKitsu)
        val byMalBytes = singleFromMap(asset.byMal)
        val byAnilistBytes = singleFromMap(asset.byAnilist)
        val byAnidbBytes = singleFromMap(asset.byAnidb)
        val byTmdbMovieBytes = singleFromMap(asset.byTmdbMovie)
        val (byTvdbBytes, byTvdbPool) = multiFromMap(asset.byTvdb)
        val (byTmdbTvBytes, byTmdbTvPool) = multiFromMap(asset.byTmdbTv)
        val (byImdbBytes, byImdbPool) = imdbFromMap(asset.byImdb)
        val byAnidbEpisodeBuilder = SortedIndexBuilder.Single()
        for ((anidb, off) in episodeRecordOffsets) byAnidbEpisodeBuilder.add(anidb.toLong(), off)
        val byAnidbEpisodeBytes = byAnidbEpisodeBuilder.toByteArray()

        // Layout: header, indexTable, [index regions], [multi-list pool], records, stringPool
        // Compute offsets
        val headerSize = BinaryFormat.HEADER_SIZE.toLong()
        val indexTableSize = BinaryFormat.INDEX_TABLE_SIZE.toLong()
        var cursor = headerSize + indexTableSize

        data class Slot(val kind: Int, val stride: Int, val bytes: ByteArray, var offset: Long = 0L) {
            val entryCount: Long get() = (bytes.size / stride).toLong()
        }
        val slots = arrayOf(
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byKitsuBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byMalBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnilistBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnidbBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byTmdbMovieBytes),
            Slot(BinaryFormat.KIND_U64_MULTI, BinaryFormat.STRIDE_U64_MULTI, byTvdbBytes),
            Slot(BinaryFormat.KIND_U64_MULTI, BinaryFormat.STRIDE_U64_MULTI, byTmdbTvBytes),
            Slot(BinaryFormat.KIND_IMDB, BinaryFormat.STRIDE_IMDB, byImdbBytes),
            Slot(BinaryFormat.KIND_U64_SINGLE, BinaryFormat.STRIDE_U64_SINGLE, byAnidbEpisodeBytes),
        )
        for (s in slots) {
            s.offset = cursor
            cursor += s.bytes.size
        }
        // Multi-list pool concatenated after index regions
        val multiPool = byTvdbPool + byTmdbTvPool + byImdbPool
        // listOffset values in index regions must be ABSOLUTE file offsets so
        // the reader can index directly into the mmap'd ByteBuffer. After the
        // indexes loop above, `cursor` is the absolute file offset where the
        // multi-list pool starts.
        val multiPoolFileOffset = cursor.toInt()
        rewriteMultiListOffsets(byTvdbBytes, base = multiPoolFileOffset)
        rewriteMultiListOffsets(byTmdbTvBytes, base = multiPoolFileOffset + byTvdbPool.size)
        rewriteImdbListOffsets(byImdbBytes, base = multiPoolFileOffset + byTvdbPool.size + byTmdbTvPool.size)

        cursor += multiPool.size
        val recordsOffset = cursor
        cursor += recordsBytes.size
        val stringPoolBytes = stringPool.toByteArray()
        val stringPoolOffset = cursor
        cursor += stringPoolBytes.size

        // Write header
        val total = cursor.toInt()
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.put(BinaryFormat.MAGIC_BYTES)
        out.putInt(BinaryFormat.SCHEMA_VERSION)
        out.putLong(parseInstantSeconds(asset.generatedAt))
        out.putInt(asset.identityRecords.size + asset.episodeMappings.size)
        out.putLong(recordsOffset)
        out.putLong(recordsBytes.size.toLong())
        out.putLong(headerSize)  // indexTableOffset
        out.putLong(stringPoolOffset)
        out.putLong(stringPoolBytes.size.toLong())
        out.putInt(0)  // reserved
        // Index table: 9 descriptors
        for (s in slots) {
            out.putInt(s.kind)
            out.putInt(s.stride)
            out.putLong(s.offset)
            out.putLong(s.entryCount)
        }
        // Index regions
        for (s in slots) out.put(s.bytes)
        out.put(multiPool)
        out.put(recordsBytes)
        out.put(stringPoolBytes)
        return out.array()
    }

    private fun parseInstantSeconds(iso: String): Long =
        runCatching { java.time.Instant.parse(iso).epochSecond }.getOrDefault(0L)

    /** Rewrite each entry's u32 listOffset at byte (i*16)+8 by adding [base]. */
    private fun rewriteMultiListOffsets(bytes: ByteArray, base: Int) {
        val stride = BinaryFormat.STRIDE_U64_MULTI
        var i = 0
        while (i < bytes.size) {
            val off = i + 8
            val cur = readI32LE(bytes, off)
            writeI32LE(bytes, off, cur + base)
            i += stride
        }
    }
    private fun rewriteImdbListOffsets(bytes: ByteArray, base: Int) {
        val stride = BinaryFormat.STRIDE_IMDB
        var i = 0
        while (i < bytes.size) {
            val off = i + 12  // u64 hash + u32 strOff = 12 bytes before listOff
            val cur = readI32LE(bytes, off)
            writeI32LE(bytes, off, cur + base)
            i += stride
        }
    }
    private fun readI32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun writeI32LE(b: ByteArray, off: Int, value: Int) {
        b[off] = (value and 0xFF).toByte()
        b[off + 1] = ((value ushr 8) and 0xFF).toByte()
        b[off + 2] = ((value ushr 16) and 0xFF).toByte()
        b[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
