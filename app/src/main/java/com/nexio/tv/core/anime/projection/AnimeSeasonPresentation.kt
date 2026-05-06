package com.nexio.tv.core.anime.projection

data class AnimeSeasonTab(
    val seasonNumber: Int,
    val title: String?,
    val episodeCount: Int?,
    val episodesKitsuMemberId: String?,
    val isFlatFallback: Boolean,
)

data class AnimeSeasonPresentation(
    val work: AnimeWorkIdentity,
    val seasons: List<AnimeSeasonTab>,
    val selectedSeason: Int,
    val source: SeasonPresentationSource,
    val confidence: CoordinateConfidence,
)
