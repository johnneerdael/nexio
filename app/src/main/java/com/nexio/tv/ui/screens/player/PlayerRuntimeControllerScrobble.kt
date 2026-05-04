package com.nexio.tv.ui.screens.player

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val season = currentSeason ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }
    val episode = currentEpisode ?: run {
        currentTraktEpisodeMapping = null
        currentTraktEpisodeMappingKey = null
        return
    }

    val mapped = traktEpisodeMappingService.prefetchEpisodeMapping(
        contentId = resolvedContentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = season,
        episode = episode
    )
    currentTraktEpisodeMapping = mapped?.let { listOf(it) }
    currentTraktEpisodeMappingKey =
        "$normalizedType|$resolvedContentId|${currentVideoId.orEmpty()}|$season|$episode"
}
