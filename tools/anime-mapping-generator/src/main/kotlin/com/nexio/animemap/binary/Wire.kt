// tools/anime-mapping-generator/src/main/kotlin/com/nexio/animemap/binary/Wire.kt
package com.nexio.animemap.binary

/** Encoder-side mirror of app's AnimeIdMapRecord. Kept separate to avoid coupling. */
data class WireAnimeIdMapRecord(
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
)

data class WireAnimeRangeRule(
    val sourceSeason: Int,
    val startEpisode: Int,
    val endEpisode: Int?,
    val targetProvider: String,
    val targetSeason: Int,
    val offset: Int,
)

data class WireAnimeExplicitMap(
    val sourceSeason: Int,
    val sourceEpisode: Int,
    val targetProvider: String,
    val targetSeason: Int,
    val targetEpisode: Int,
)

data class WireAnimeEpisodeMappingRecord(
    val anidb: String,
    val name: String? = null,
    val tvdbSeriesId: String? = null,
    val tmdbTvId: String? = null,
    val ranges: List<WireAnimeRangeRule> = emptyList(),
    val explicitMaps: List<WireAnimeExplicitMap> = emptyList(),
    val evidence: List<String> = emptyList(),
)
