package com.nexio.animemap.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class OverlayFile(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "entries") val entries: List<OverlayEntry>
)

@JsonClass(generateAdapter = true)
data class OverlayEntry(
    @Json(name = "anidb") val anidb: String,
    @Json(name = "mode") val mode: String,
    @Json(name = "reason") val reason: String,
    @Json(name = "target") val target: String? = null,
    @Json(name = "patch") val patch: OverlayPatch? = null,
    @Json(name = "record") val record: Map<String, Any?>? = null
)

@JsonClass(generateAdapter = true)
data class OverlayPatch(
    @Json(name = "tvdbSeason") val tvdbSeason: String? = null,
    @Json(name = "tmdbSeason") val tmdbSeason: String? = null,
    @Json(name = "tvdbEpisodeOffset") val tvdbEpisodeOffset: Int? = null,
    @Json(name = "tmdbEpisodeOffset") val tmdbEpisodeOffset: Int? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "addRanges") val addRanges: List<RangeRule> = emptyList(),
    @Json(name = "removeRanges") val removeRanges: List<RangeRuleKey> = emptyList(),
    @Json(name = "addExplicitMaps") val addExplicitMaps: List<ExplicitMap> = emptyList(),
    @Json(name = "removeExplicitMaps") val removeExplicitMaps: List<ExplicitMapKey> = emptyList()
)

@JsonClass(generateAdapter = true)
data class RangeRuleKey(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "startEpisode") val startEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String
)

@JsonClass(generateAdapter = true)
data class ExplicitMapKey(
    @Json(name = "sourceSeason") val sourceSeason: Int,
    @Json(name = "sourceEpisode") val sourceEpisode: Int,
    @Json(name = "targetProvider") val targetProvider: String
)

object OverlayMode {
    const val PATCH_IDENTITY = "patch-identity"
    const val PATCH_MAPPING = "patch-mapping"
    const val REPLACE = "replace"
    const val DROP = "drop"

    val ALL = setOf(PATCH_IDENTITY, PATCH_MAPPING, REPLACE, DROP)
}

object OverlayTarget {
    const val IDENTITY = "identity"
    const val MAPPING = "mapping"
}
