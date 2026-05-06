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
