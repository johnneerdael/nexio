package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class NexioAnimeMap(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "mappingPolicyVersion") val mappingPolicyVersion: Int,
    @Json(name = "generatedAt") val generatedAt: String,
    @Json(name = "counts") val counts: AssetCounts,
    @Json(name = "identityRecordsByKitsu") val identityRecordsByKitsu: Map<String, IdentityRecord>,
    @Json(name = "episodeMappingsByAnidb") val episodeMappingsByAnidb: Map<String, EpisodeMappingRecord>,
    @Json(name = "indexes") val indexes: MapIndexes
)

@JsonClass(generateAdapter = true)
data class AssetCounts(
    @Json(name = "identityRecords") val identityRecords: Int,
    @Json(name = "episodeMappingRecords") val episodeMappingRecords: Int,
    @Json(name = "skippedCount") val skippedCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class IdentityRecord(
    @Json(name = "kitsu") val kitsu: String,
    @Json(name = "mal") val mal: String? = null,
    @Json(name = "anilist") val anilist: String? = null,
    @Json(name = "anidb") val anidb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "sourceType") val sourceType: String? = null,
    @Json(name = "tvdbSeason") val tvdbSeason: String? = null,
    @Json(name = "tmdbSeason") val tmdbSeason: String? = null,
    @Json(name = "tvdbEpisodeOffset") val tvdbEpisodeOffset: Int? = null,
    @Json(name = "tmdbEpisodeOffset") val tmdbEpisodeOffset: Int? = null,
    @Json(name = "hasMappingRules") val hasMappingRules: Boolean = false,
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class EpisodeMappingRecord(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "name") val name: String? = null,
    @Json(name = "tvdbSeriesId") val tvdbSeriesId: String? = null,
    @Json(name = "tmdbTvId") val tmdbTvId: String? = null,
    @Json(name = "ranges") val ranges: List<RangeRule> = emptyList(),
    @Json(name = "explicitMaps") val explicitMaps: List<ExplicitMap> = emptyList(),
    @Json(name = "evidence") val evidence: List<String> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RangeRule(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "endEpisode") val endEpisode: Int? = null,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "offset") val offset: Int
)

@JsonClass(generateAdapter = true)
data class ExplicitMap(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String,
    @Json(name = "targetSeason") val targetSeason: Int,
    @Json(name = "targetEpisode") val targetEpisode: Int
)

@JsonClass(generateAdapter = true)
data class MapIndexes(
    @Json(name = "byKitsu") val byKitsu: Map<String, String> = emptyMap(),
    @Json(name = "byMal") val byMal: Map<String, String> = emptyMap(),
    @Json(name = "byAnilist") val byAnilist: Map<String, String> = emptyMap(),
    @Json(name = "byAnidb") val byAnidb: Map<String, String> = emptyMap(),
    @Json(name = "byTvdb") val byTvdb: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbTv") val byTmdbTv: Map<String, List<String>> = emptyMap(),
    @Json(name = "byTmdbMovie") val byTmdbMovie: Map<String, String> = emptyMap(),
    @Json(name = "byImdb") val byImdb: Map<String, List<String>> = emptyMap()
)
