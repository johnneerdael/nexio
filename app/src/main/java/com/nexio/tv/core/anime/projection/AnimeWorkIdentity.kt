package com.nexio.tv.core.anime.projection

import com.nexio.tv.domain.model.ProviderIds

@JvmInline
value class AnimeWorkGroupKey(val value: String) {
    companion object {
        fun preferred(
            tvdbId: String?,
            imdbId: String?,
            tmdbId: String?,
            sourceKitsuId: String?,
        ): AnimeWorkGroupKey = AnimeWorkGroupKey(
            when {
                !tvdbId.isNullOrBlank() -> "anime-work:tvdb:$tvdbId"
                !imdbId.isNullOrBlank() -> "anime-work:imdb:$imdbId"
                !tmdbId.isNullOrBlank() -> "anime-work:tmdb:$tmdbId"
                !sourceKitsuId.isNullOrBlank() -> "anime-work:kitsu:$sourceKitsuId"
                else -> "anime-work:unknown"
            }
        )
    }
}

data class AnimeWorkIdentity(
    val groupKey: AnimeWorkGroupKey,
    val primaryKitsuId: String?,
    val memberKitsuIds: Set<String>,
    val providerIds: ProviderIds,
    val confidence: AnimeGroupingConfidence,
    val evidence: List<String>,
)
