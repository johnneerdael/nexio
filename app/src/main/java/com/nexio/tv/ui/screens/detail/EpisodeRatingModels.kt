package com.nexio.tv.ui.screens.detail

import com.nexio.tv.R
import com.nexio.tv.domain.model.TitleRatingSource

enum class EpisodeRatingSource {
    IMDB,
    OMDB,
    TMDB
}

data class EpisodeRating(
    val value: Double,
    val source: EpisodeRatingSource
)

data class EpisodeRatingBadgeUi(
    val logoRes: Int,
    val contentDescription: String
)

data class RatingBadgeUi(
    val logoRes: Int,
    val contentDescription: String
)

internal fun resolveEpisodeRatings(
    tmdbRatings: Map<Pair<Int, Int>, Double>,
    omdbRatings: Map<Pair<Int, Int>, Double>
): Map<Pair<Int, Int>, EpisodeRating> {
    val resolved = LinkedHashMap<Pair<Int, Int>, EpisodeRating>(tmdbRatings.size + omdbRatings.size)

    tmdbRatings.forEach { (key, value) ->
        resolved[key] = EpisodeRating(value = value, source = EpisodeRatingSource.TMDB)
    }
    omdbRatings.forEach { (key, value) ->
        resolved[key] = EpisodeRating(value = value, source = EpisodeRatingSource.OMDB)
    }

    return resolved
}

internal fun resolveEpisodeRatingValues(
    ratings: Map<Pair<Int, Int>, EpisodeRating>
): Map<Pair<Int, Int>, Double> = ratings.mapValues { (_, rating) -> rating.value }

internal fun episodeRatingBadge(source: EpisodeRatingSource): EpisodeRatingBadgeUi {
    return when (source) {
        EpisodeRatingSource.IMDB -> EpisodeRatingBadgeUi(
            logoRes = R.raw.imdb_logo_2016,
            contentDescription = "IMDb"
        )
        EpisodeRatingSource.OMDB -> EpisodeRatingBadgeUi(
            logoRes = R.raw.imdb_logo_2016,
            contentDescription = "IMDb"
        )
        EpisodeRatingSource.TMDB -> EpisodeRatingBadgeUi(
            logoRes = R.raw.mdblist_tmdb,
            contentDescription = "TMDB"
        )
    }
}

internal fun titleRatingBadge(source: TitleRatingSource): RatingBadgeUi {
    return when (source) {
        TitleRatingSource.IMDB -> RatingBadgeUi(
            logoRes = R.raw.imdb_logo_2016,
            contentDescription = "IMDb"
        )
        TitleRatingSource.TMDB -> RatingBadgeUi(
            logoRes = R.raw.mdblist_tmdb,
            contentDescription = "TMDB"
        )
    }
}
