package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.Meta

interface AnimeSeasonDetailRepository {
    /**
     * Given a base [Meta] and a Kitsu source identity, resolve the work group, build season tabs,
     * pick the correct season (click-source-aware), and hydrate the episode list for that season.
     * Returns a [Meta] whose videos carry correct Kitsu (season, episode) keys.
     */
    suspend fun resolveAndHydrateAnimeDetail(
        baseMeta: Meta,
        sourceKitsuId: String,
        requestedSeason: Int?,
    ): AnimeDetailResult
}

sealed interface AnimeDetailResult {
    data class Success(val meta: Meta, val presentation: AnimeSeasonPresentation) : AnimeDetailResult
    data class Error(val message: String) : AnimeDetailResult
}
