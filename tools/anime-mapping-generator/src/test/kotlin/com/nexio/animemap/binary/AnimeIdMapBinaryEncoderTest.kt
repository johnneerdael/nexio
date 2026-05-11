package com.nexio.animemap.binary

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AnimeIdMapBinaryEncoderTest {
    @Test
    fun `identity record round-trips through writer + manual reader`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = WireAnimeIdMapRecord(
            kitsu = "265",
            mal = "290",
            anilist = "290",
            anidb = "1",
            tmdb = "26209",
            tvdb = "72025",
            imdb = "tt0286390",
            mediaType = "series",
            sourceType = "TV",
            tvdbSeason = "1",
            tmdbSeason = "1",
            evidence = listOf("fribb.kitsu=265", "fribb.tvdb=72025")
        )
        val bytesWritten = AnimeIdMapBinaryEncoder.writeIdentityRecord(out, rec, pool)
        val bytes = out.toByteArray()
        assertEquals(bytesWritten, bytes.size)
        // recordKind byte
        assertEquals(BinaryFormat.RECORD_KIND_IDENTITY, bytes[0])
        // Decode using the same helper the reader will use
        val decoded = TestRecordDecoder(bytes, pool.toByteArray()).decodeIdentityAt(0)
        assertEquals(rec.kitsu, decoded.kitsu)
        assertEquals(rec.mal, decoded.mal)
        assertEquals(rec.anilist, decoded.anilist)
        assertEquals(rec.anidb, decoded.anidb)
        assertEquals(rec.tmdb, decoded.tmdb)
        assertEquals(rec.tvdb, decoded.tvdb)
        assertEquals(rec.imdb, decoded.imdb)
        assertEquals(rec.mediaType, decoded.mediaType)
        assertEquals(rec.sourceType?.lowercase(), decoded.sourceType?.lowercase())
        assertEquals(rec.tvdbSeason, decoded.tvdbSeason)
        assertEquals(rec.tmdbSeason, decoded.tmdbSeason)
        assertEquals(rec.evidence, decoded.evidence)
    }

    @Test
    fun `identity record with mostly-null fields is compact`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = WireAnimeIdMapRecord(kitsu = "99999")
        val bytesWritten = AnimeIdMapBinaryEncoder.writeIdentityRecord(out, rec, pool)
        // kind(1) + presence(1) + presence2(1) + varint kitsuId(3 for 99999)
        // 99999 = 0x1869F -> varint: 0x9F 0x8D 0x06 (3 bytes)
        assertEquals(6, bytesWritten)
    }

    @Test
    fun `episode record round-trips`() {
        val pool = StringPoolBuilder()
        val out = ByteArrayOutputStream()
        val rec = WireAnimeEpisodeMappingRecord(
            anidb = "69",
            name = "One Piece",
            tvdbSeriesId = "81797",
            tmdbTvId = "37854",
            ranges = listOf(
                WireAnimeRangeRule(1, 1, 8, "TVDB", 1, 0),
                WireAnimeRangeRule(1, 892, 1085, "TVDB", 21, -891)
            ),
            explicitMaps = listOf(
                WireAnimeExplicitMap(0, 1, "TVDB", 0, 27)
            ),
            evidence = listOf("scudlee.one-piece")
        )
        val written = AnimeIdMapBinaryEncoder.writeEpisodeRecord(out, rec, pool)
        val bytes = out.toByteArray()
        assertEquals(written, bytes.size)
        assertEquals(BinaryFormat.RECORD_KIND_EPISODE, bytes[0])

        val decoded = TestEpisodeDecoder(bytes, pool.toByteArray()).decodeAt(0)
        assertEquals(rec, decoded)
    }

    @Test
    fun `encode produces deterministic byte-identical output for identical input`() {
        val input = sampleAsset()
        val a = AnimeIdMapBinaryEncoder.encode(input)
        val b = AnimeIdMapBinaryEncoder.encode(input)
        assertArrayEquals(a, b)
    }

    @Test
    fun `encode header validates magic and schema version`() {
        val bytes = AnimeIdMapBinaryEncoder.encode(sampleAsset())
        assertEquals('N'.code.toByte(), bytes[0])
        assertEquals('X'.code.toByte(), bytes[1])
        assertEquals('A'.code.toByte(), bytes[2])
        assertEquals('I'.code.toByte(), bytes[3])
        // schemaVersion u32 LE
        val schema = ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(BinaryFormat.SCHEMA_VERSION, schema)
    }

    private fun sampleAsset(): WireAnimeIdMapAsset {
        val r1 = WireAnimeIdMapRecord(
            kitsu = "265", mal = "290", anidb = "1", tvdb = "72025", imdb = "tt0286390",
            mediaType = "series", sourceType = "TV"
        )
        val r2 = WireAnimeIdMapRecord(kitsu = "99999", mediaType = "series", sourceType = "TV")
        return WireAnimeIdMapAsset(
            generatedAt = "2026-05-06T00:00:00Z",
            identityRecords = listOf(r1, r2),
            episodeMappings = emptyList(),
            byKitsu = mapOf("265" to "265", "99999" to "99999"),
            byMal = mapOf("290" to "265"),
            byAnilist = emptyMap(),
            byAnidb = mapOf("1" to "265"),
            byTmdbMovie = emptyMap(),
            byTvdb = mapOf("72025" to listOf("265")),
            byTmdbTv = emptyMap(),
            byImdb = mapOf("tt0286390" to listOf("265")),
        )
    }
}

/** Test-only decoder mirroring what AnimeIdMapBinaryReader will implement. */
private class TestRecordDecoder(private val records: ByteArray, private val stringPool: ByteArray) {
    fun decodeIdentityAt(offset: Int): WireAnimeIdMapRecord {
        var p = offset
        check(records[p].toInt() == 0) { "expected identity record at $p" }
        p += 1
        val presence = records[p].toInt() and 0xFF; p += 1
        val presence2 = records[p].toInt() and 0xFF; p += 1
        val (kitsu, after1) = readVarintNumeric(records, p); p = after1
        val mal = if (presence and BinaryFormat.P_MAL != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val anilist = if (presence and BinaryFormat.P_ANILIST != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val anidb = if (presence and BinaryFormat.P_ANIDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tmdb = if (presence and BinaryFormat.P_TMDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tvdb = if (presence and BinaryFormat.P_TVDB != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val imdb = if (presence and BinaryFormat.P_IMDB != 0) { val off = readI32(records, p); p += 4; readPoolString(off) } else null
        val mediaType = if (presence and BinaryFormat.P_MEDIA_TYPE != 0) { val b = records[p].toInt(); p += 1; BinaryFormat.MEDIA_TYPE_TABLE[b] } else null
        val sourceType = if (presence and BinaryFormat.P_SOURCE_TYPE != 0) { val b = records[p].toInt(); p += 1; BinaryFormat.SOURCE_TYPE_TABLE[b] } else null
        val tvdbSeason = if (presence2 and BinaryFormat.P2_TVDB_SEASON != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tmdbSeason = if (presence2 and BinaryFormat.P2_TMDB_SEASON != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tvdbEpOff = if (presence2 and BinaryFormat.P2_TVDB_EP_OFFSET != 0) { val (v, n) = readVarintSigned(records, p); p = n; v } else null
        val tmdbEpOff = if (presence2 and BinaryFormat.P2_TMDB_EP_OFFSET != 0) { val (v, n) = readVarintSigned(records, p); p = n; v } else null
        val hasMappingRules = presence2 and BinaryFormat.P2_HAS_MAPPING_RULES != 0
        val evidence = if (presence2 and BinaryFormat.P2_HAS_EVIDENCE != 0) {
            val (count, n1) = readVarintNumeric(records, p); p = n1
            val list = ArrayList<String>(count.toInt())
            repeat(count.toInt()) {
                val off = readI32(records, p); p += 4
                list.add(readPoolString(off)!!)
            }
            list
        } else emptyList()
        return WireAnimeIdMapRecord(
            kitsu = kitsu.toString(), mal = mal?.toString(), anilist = anilist?.toString(),
            anidb = anidb?.toString(), tmdb = tmdb?.toString(), tvdb = tvdb?.toString(),
            imdb = imdb, mediaType = mediaType, sourceType = sourceType,
            tvdbSeason = tvdbSeason?.toString(), tmdbSeason = tmdbSeason?.toString(),
            tvdbEpisodeOffset = tvdbEpOff, tmdbEpisodeOffset = tmdbEpOff,
            hasMappingRules = hasMappingRules, evidence = evidence
        )
    }
    private fun readVarintNumeric(b: ByteArray, off: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = off
        while (true) {
            val byte = b[i].toInt() and 0xFF; i++
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to i
            shift += 7
        }
    }
    private fun readVarintSigned(b: ByteArray, off: Int): Pair<Int, Int> {
        val (raw, next) = readVarintNumeric(b, off)
        val v = ((raw ushr 1).toInt()) xor (-(raw.toInt() and 1))
        return v to next
    }
    private fun readI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun readPoolString(off: Int): String? {
        if (off == -1) return null
        val (len, after) = readVarintNumeric(stringPool, off)
        return String(stringPool, after, len.toInt(), Charsets.UTF_8)
    }
}

/** Test-only decoder for episode records. */
private class TestEpisodeDecoder(private val records: ByteArray, private val stringPool: ByteArray) {
    fun decodeAt(offset: Int): WireAnimeEpisodeMappingRecord {
        var p = offset
        check(records[p].toInt() == BinaryFormat.RECORD_KIND_EPISODE.toInt()) { "expected episode record at $p" }
        p += 1
        val presence = records[p].toInt() and 0xFF; p += 1
        val (anidb, n0) = readVarintNumeric(records, p); p = n0
        val name = if (presence and 0x01 != 0) { val off = readI32(records, p); p += 4; readPoolString(off) } else null
        val tvdbSeriesId = if (presence and 0x02 != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val tmdbTvId = if (presence and 0x04 != 0) { val (v, n) = readVarintNumeric(records, p); p = n; v } else null
        val (rangesCount, n1) = readVarintNumeric(records, p); p = n1
        val ranges = ArrayList<WireAnimeRangeRule>(rangesCount.toInt())
        repeat(rangesCount.toInt()) {
            val srcSeason = records[p].toInt() and 0xFF; p += 1
            val startEp = readU16(records, p); p += 2
            val endEpRaw = readU16(records, p); p += 2
            val provByte = records[p].toInt() and 0xFF; p += 1
            val tgtSeason = records[p].toInt() and 0xFF; p += 1
            val off = readI16(records, p); p += 2
            val hasEnd = records[p].toInt() and 0xFF; p += 1
            ranges.add(
                WireAnimeRangeRule(
                    sourceSeason = srcSeason,
                    startEpisode = startEp,
                    endEpisode = if (hasEnd == 0) null else endEpRaw,
                    targetProvider = BinaryFormat.PROVIDER_TABLE[provByte],
                    targetSeason = tgtSeason,
                    offset = off,
                )
            )
        }
        val (explicitCount, n2) = readVarintNumeric(records, p); p = n2
        val explicits = ArrayList<WireAnimeExplicitMap>(explicitCount.toInt())
        repeat(explicitCount.toInt()) {
            val srcSeason = records[p].toInt() and 0xFF; p += 1
            val srcEp = readU16(records, p); p += 2
            val provByte = records[p].toInt() and 0xFF; p += 1
            val tgtSeason = records[p].toInt() and 0xFF; p += 1
            val tgtEp = readU16(records, p); p += 2
            explicits.add(
                WireAnimeExplicitMap(
                    sourceSeason = srcSeason,
                    sourceEpisode = srcEp,
                    targetProvider = BinaryFormat.PROVIDER_TABLE[provByte],
                    targetSeason = tgtSeason,
                    targetEpisode = tgtEp,
                )
            )
        }
        val evidence = if (presence and 0x08 != 0) {
            val (count, n3) = readVarintNumeric(records, p); p = n3
            val list = ArrayList<String>(count.toInt())
            repeat(count.toInt()) {
                val off = readI32(records, p); p += 4
                list.add(readPoolString(off)!!)
            }
            list
        } else emptyList()
        return WireAnimeEpisodeMappingRecord(
            anidb = anidb.toString(),
            name = name,
            tvdbSeriesId = tvdbSeriesId?.toString(),
            tmdbTvId = tmdbTvId?.toString(),
            ranges = ranges,
            explicitMaps = explicits,
            evidence = evidence,
        )
    }

    private fun readVarintNumeric(b: ByteArray, off: Int): Pair<Long, Int> {
        var result = 0L; var shift = 0; var i = off
        while (true) {
            val byte = b[i].toInt() and 0xFF; i++
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result to i
            shift += 7
        }
    }
    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
    private fun readI16(b: ByteArray, off: Int): Int {
        val raw = readU16(b, off)
        return if (raw and 0x8000 != 0) raw or 0xFFFF.inv() else raw  // sign extend
    }
    private fun readI32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
        ((b[off + 1].toInt() and 0xFF) shl 8) or
        ((b[off + 2].toInt() and 0xFF) shl 16) or
        ((b[off + 3].toInt() and 0xFF) shl 24)
    private fun readPoolString(off: Int): String? {
        if (off == -1) return null
        val (len, after) = readVarintNumeric(stringPool, off)
        return String(stringPool, after, len.toInt(), Charsets.UTF_8)
    }
}
