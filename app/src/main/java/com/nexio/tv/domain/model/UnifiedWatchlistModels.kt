package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

enum class UnifiedWatchlistSource {
    TRAKT,
    SIMKL,
    MDBLIST,
    LOCAL
}

enum class UnifiedWatchlistMembershipConfidence {
    STRONG,
    LOW
}

@Immutable
data class UnifiedWatchlistSourceRef(
    val source: UnifiedWatchlistSource,
    val rawKey: String,
    val listKey: String? = null
)

@Immutable
data class UnifiedWatchlistSourceItem(
    val source: UnifiedWatchlistSource,
    val rawKey: String,
    val contentType: ContentType,
    val title: String? = null,
    val year: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val traktId: Int? = null,
    val simklId: Int? = null,
    val showImdbId: String? = null,
    val showTmdbId: Int? = null,
    val showTvdbId: Int? = null,
    val showTraktId: Int? = null,
    val showSimklId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val listKey: String? = null
) {
    companion object {
        fun traktMovie(
            imdb: String? = null,
            tmdb: Int? = null,
            tvdb: Int? = null,
            trakt: Int? = null,
            title: String? = null,
            year: Int? = null
        ): UnifiedWatchlistSourceItem = UnifiedWatchlistSourceItem(
            source = UnifiedWatchlistSource.TRAKT,
            rawKey = firstKey("trakt:movie", imdb, tmdb, tvdb, trakt),
            contentType = ContentType.MOVIE,
            title = title,
            year = year,
            imdbId = imdb,
            tmdbId = tmdb,
            tvdbId = tvdb,
            traktId = trakt
        )

        fun simklMovie(
            imdb: String? = null,
            tmdb: Int? = null,
            tvdb: Int? = null,
            simkl: Int? = null,
            title: String? = null,
            year: Int? = null
        ): UnifiedWatchlistSourceItem = UnifiedWatchlistSourceItem(
            source = UnifiedWatchlistSource.SIMKL,
            rawKey = firstKey("simkl:movie", imdb, tmdb, tvdb, simkl),
            contentType = ContentType.MOVIE,
            title = title,
            year = year,
            imdbId = imdb,
            tmdbId = tmdb,
            tvdbId = tvdb,
            simklId = simkl
        )

        fun simklSeries(
            tmdb: Int? = null,
            tvdb: Int? = null,
            simkl: Int? = null,
            title: String? = null,
            year: Int? = null
        ): UnifiedWatchlistSourceItem = UnifiedWatchlistSourceItem(
            source = UnifiedWatchlistSource.SIMKL,
            rawKey = firstKey("simkl:series", tmdb, tvdb, simkl),
            contentType = ContentType.SERIES,
            title = title,
            year = year,
            tmdbId = tmdb,
            tvdbId = tvdb,
            simklId = simkl
        )

        fun traktEpisode(
            showImdb: String? = null,
            showTmdb: Int? = null,
            showTvdb: Int? = null,
            showTrakt: Int? = null,
            season: Int,
            episode: Int,
            title: String? = null,
            year: Int? = null
        ): UnifiedWatchlistSourceItem = UnifiedWatchlistSourceItem(
            source = UnifiedWatchlistSource.TRAKT,
            rawKey = buildString {
                append(firstKey("trakt:episode", showImdb, showTmdb, showTvdb, showTrakt))
                append(":s")
                append(season)
                append("e")
                append(episode)
            },
            contentType = ContentType.SERIES,
            title = title,
            year = year,
            showImdbId = showImdb,
            showTmdbId = showTmdb,
            showTvdbId = showTvdb,
            showTraktId = showTrakt,
            season = season,
            episode = episode
        )

        fun localTitle(type: String, title: String, year: Int?): UnifiedWatchlistSourceItem =
            titleOnly(UnifiedWatchlistSource.LOCAL, type, title, year)

        fun traktTitle(type: String, title: String, year: Int?): UnifiedWatchlistSourceItem =
            titleOnly(UnifiedWatchlistSource.TRAKT, type, title, year)

        private fun titleOnly(
            source: UnifiedWatchlistSource,
            type: String,
            title: String,
            year: Int?
        ): UnifiedWatchlistSourceItem = UnifiedWatchlistSourceItem(
            source = source,
            rawKey = "${source.name.lowercase()}:${ContentType.fromString(type).toApiString()}:${title.trim()}:${year ?: ""}",
            contentType = ContentType.fromString(type),
            title = title,
            year = year
        )

        private fun firstKey(prefix: String, vararg values: Any?): String {
            return values.firstOrNull { it != null }?.let { "$prefix:$it" } ?: prefix
        }
    }
}

@Immutable
data class UnifiedWatchlistMembership(
    val authorityKey: String,
    val contentType: ContentType,
    val presentIn: Set<UnifiedWatchlistSource>,
    val sourceRefs: List<UnifiedWatchlistSourceRef>,
    val confidence: UnifiedWatchlistMembershipConfidence,
    val title: String? = null,
    val year: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val tvdbId: Int? = null,
    val traktId: Int? = null,
    val simklId: Int? = null,
    val showTmdbId: Int? = null,
    val season: Int? = null,
    val episode: Int? = null
)

@Immutable
data class UnifiedWatchlistRowItem(
    val membership: UnifiedWatchlistMembership,
    val displayItem: ResolvedDisplayItem
)
