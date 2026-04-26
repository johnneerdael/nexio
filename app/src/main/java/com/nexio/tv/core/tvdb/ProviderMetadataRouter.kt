package com.nexio.tv.core.tvdb

interface ProviderMetadataRouter {
    suspend fun fetchEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<TvMetadataEnrichment>

    suspend fun fetchEpisodeEnrichment(
        request: TvMetadataRequest
    ): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>>

    suspend fun fetchSeasonEpisodes(
        contentId: String,
        fallbackContentId: String?,
        seasonNumber: Int,
        language: String? = null
    ): TvMetadataDecision<List<TvSeasonEpisode>>
}
