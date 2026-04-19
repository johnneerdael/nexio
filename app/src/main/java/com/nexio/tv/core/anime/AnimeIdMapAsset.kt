package com.nexio.tv.core.anime

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class AnimeIdMapAsset(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "recordsByKitsu") val recordsByKitsu: Map<String, AnimeIdMapRecord> = emptyMap(),
    @Json(name = "byKitsu") val byKitsu: Map<String, String> = emptyMap(),
    @Json(name = "byMal") val byMal: Map<String, String> = emptyMap(),
    @Json(name = "byAnilist") val byAnilist: Map<String, String> = emptyMap(),
    @Json(name = "byAnidb") val byAnidb: Map<String, String> = emptyMap(),
    @Json(name = "byTvdb") val byTvdb: Map<String, String> = emptyMap(),
    @Json(name = "byTmdbMovie") val byTmdbMovie: Map<String, String> = emptyMap(),
    @Json(name = "byTmdbSeries") val byTmdbSeries: Map<String, String> = emptyMap(),
    @Json(name = "byImdb") val byImdb: Map<String, String> = emptyMap()
)

@JsonClass(generateAdapter = true)
data class AnimeIdMapRecord(
    @Json(name = "kitsu") val kitsu: String,
    @Json(name = "mal") val mal: String? = null,
    @Json(name = "anilist") val anilist: String? = null,
    @Json(name = "anidb") val anidb: String? = null,
    @Json(name = "tmdb") val tmdb: String? = null,
    @Json(name = "tvdb") val tvdb: String? = null,
    @Json(name = "imdb") val imdb: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "sourceType") val sourceType: String? = null
)

enum class ContentMediaKind {
    MOVIE,
    SERIES
}
