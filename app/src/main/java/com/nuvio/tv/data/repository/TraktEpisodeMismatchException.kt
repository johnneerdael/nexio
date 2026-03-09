package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress

data class TraktEpisodeMismatchException(
    val originalSeason: Int,
    val originalEpisode: Int,
    val suggestedSeason: Int,
    val suggestedEpisode: Int,
    val suggestedTitle: String?,
    val matchMethod: String,
    val originalProgress: WatchProgress
) : Exception(
    "Episode mismatch: addon S${originalSeason}E${originalEpisode} → Trakt S${suggestedSeason}E${suggestedEpisode}"
)
