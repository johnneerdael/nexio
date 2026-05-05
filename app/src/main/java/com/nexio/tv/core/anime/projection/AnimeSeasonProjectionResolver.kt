package com.nexio.tv.core.anime.projection

import com.nexio.tv.core.anime.AnimeStremioId

data class AnimeSourceIdentity(
    val sourceKitsuId: String?,
    val animeStremioId: AnimeStremioId?,
)

interface AnimeSeasonProjectionResolver {

    suspend fun resolveWork(source: AnimeSourceIdentity): AnimeWorkIdentity

    suspend fun resolveSeasonPresentation(
        work: AnimeWorkIdentity,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeSeasonPresentation

    suspend fun resolveEpisodeProjection(
        work: AnimeWorkIdentity,
        sourceEpisode: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection
}
