// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/EncodeMain.kt
package com.nexio.animemap.binary

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import okio.buffer
import okio.source
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object EncodeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2) { "usage: EncodeMain <json-input> <bin-output>" }
        val input = File(args[0])
        val output = File(args[1])
        require(input.exists()) { "input not found: $input" }

        val moshi = Moshi.Builder().build()
        val adapter: JsonAdapter<RawJsonAsset> = moshi.adapter(RawJsonAsset::class.java)
        // Stream-parse the JSON to avoid materializing it as a String (mirrors CLAUDE.md rule #3).
        val raw = input.source().use { source ->
            source.buffer().use { bufferedSource ->
                adapter.fromJson(bufferedSource)
            }
        } ?: error("failed to parse $input")
        val asset = raw.toWire()
        val bytes = AnimeIdMapBinaryEncoder.encode(asset)

        output.parentFile?.mkdirs()
        val tmp = File(output.parentFile, "${output.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(
            tmp.toPath(),
            output.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        println("encoded ${bytes.size} bytes -> $output")
    }
}

// ── JSON model (Moshi-mapped, mirrors AnimeIdMapAsset shape) ─────────────

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawJsonAsset(
    val schemaVersion: Int,
    val mappingPolicyVersion: Int = 1,
    val generatedAt: String? = null,
    val identityRecordsByKitsu: Map<String, RawIdentityRecord> = emptyMap(),
    val episodeMappingsByAnidb: Map<String, RawEpisodeRecord> = emptyMap(),
    val indexes: RawIndexes = RawIndexes(),
) {
    fun toWire(): WireAnimeIdMapAsset = WireAnimeIdMapAsset(
        generatedAt = generatedAt ?: "1970-01-01T00:00:00Z",
        identityRecords = identityRecordsByKitsu.values.map { it.toWire() },
        episodeMappings = episodeMappingsByAnidb.values.map { it.toWire() },
        byKitsu = indexes.byKitsu,
        byMal = indexes.byMal,
        byAnilist = indexes.byAnilist,
        byAnidb = indexes.byAnidb,
        byTmdbMovie = indexes.byTmdbMovie,
        byTvdb = indexes.byTvdb,
        byTmdbTv = indexes.byTmdbTv,
        byImdb = indexes.byImdb,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawIdentityRecord(
    val kitsu: String,
    val mal: String? = null,
    val anilist: String? = null,
    val anidb: String? = null,
    val tmdb: String? = null,
    val tvdb: String? = null,
    val imdb: String? = null,
    val mediaType: String? = null,
    val sourceType: String? = null,
    val tvdbSeason: String? = null,
    val tmdbSeason: String? = null,
    val tvdbEpisodeOffset: Int? = null,
    val tmdbEpisodeOffset: Int? = null,
    val hasMappingRules: Boolean = false,
    val evidence: List<String> = emptyList(),
) {
    fun toWire(): WireAnimeIdMapRecord = WireAnimeIdMapRecord(
        kitsu = kitsu,
        mal = mal,
        anilist = anilist,
        anidb = anidb,
        tmdb = tmdb,
        tvdb = tvdb,
        imdb = imdb,
        mediaType = mediaType,
        sourceType = sourceType,
        tvdbSeason = tvdbSeason,
        tmdbSeason = tmdbSeason,
        tvdbEpisodeOffset = tvdbEpisodeOffset,
        tmdbEpisodeOffset = tmdbEpisodeOffset,
        hasMappingRules = hasMappingRules,
        evidence = evidence,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawEpisodeRecord(
    val anidb: String,
    val name: String? = null,
    val tvdbSeriesId: String? = null,
    val tmdbTvId: String? = null,
    val ranges: List<RawRange> = emptyList(),
    val explicitMaps: List<RawExplicit> = emptyList(),
    val evidence: List<String> = emptyList(),
) {
    fun toWire(): WireAnimeEpisodeMappingRecord = WireAnimeEpisodeMappingRecord(
        anidb = anidb,
        name = name,
        tvdbSeriesId = tvdbSeriesId,
        tmdbTvId = tmdbTvId,
        ranges = ranges.map {
            WireAnimeRangeRule(
                sourceSeason = it.sourceSeason,
                startEpisode = it.startEpisode,
                endEpisode = it.endEpisode,
                targetProvider = it.targetProvider,
                targetSeason = it.targetSeason,
                offset = it.offset,
            )
        },
        explicitMaps = explicitMaps.map {
            WireAnimeExplicitMap(
                sourceSeason = it.sourceSeason,
                sourceEpisode = it.sourceEpisode,
                targetProvider = it.targetProvider,
                targetSeason = it.targetSeason,
                targetEpisode = it.targetEpisode,
            )
        },
        evidence = evidence,
    )
}

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawRange(
    val sourceSeason: Int,
    val startEpisode: Int,
    val endEpisode: Int? = null,
    val targetProvider: String,
    val targetSeason: Int,
    val offset: Int,
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawExplicit(
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,
    val targetSeason: Int,
    val targetEpisode: Int,
)

@com.squareup.moshi.JsonClass(generateAdapter = true)
data class RawIndexes(
    val byKitsu: Map<String, String> = emptyMap(),
    val byMal: Map<String, String> = emptyMap(),
    val byAnilist: Map<String, String> = emptyMap(),
    val byAnidb: Map<String, String> = emptyMap(),
    val byTvdb: Map<String, List<String>> = emptyMap(),
    val byTmdbTv: Map<String, List<String>> = emptyMap(),
    val byTmdbMovie: Map<String, String> = emptyMap(),
    val byImdb: Map<String, List<String>> = emptyMap(),
)
