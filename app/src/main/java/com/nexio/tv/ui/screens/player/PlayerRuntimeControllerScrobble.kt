package com.nexio.tv.ui.screens.player

internal fun PlayerRuntimeController.currentEpisodeMappingCacheKey(): String? {
    val resolvedContentId = contentId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val resolvedType = contentType?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val season = currentSeason ?: return null
    val episode = currentEpisode ?: return null
    val videoId = currentVideoId?.trim().orEmpty()
    return "$resolvedType|$resolvedContentId|$videoId|$season|$episode"
}

internal suspend fun PlayerRuntimeController.warmTraktEpisodeMappingForCurrentPlayback() {
    val normalizedType = contentType?.lowercase()
    if (normalizedType !in listOf("series", "tv")) {
        clearTraktEpisodeMapping()
        return
    }
    val resolvedContentId = contentId?.takeIf { it.isNotBlank() } ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val season = currentSeason ?: run {
        clearTraktEpisodeMapping()
        return
    }
    val episode = currentEpisode ?: run {
        clearTraktEpisodeMapping()
        return
    }

    currentTraktEpisodeMapping = traktEpisodeMappingService.prefetchEpisodeMapping(
        contentId = resolvedContentId,
        contentType = contentType,
        videoId = currentVideoId,
        season = season,
        episode = episode
    )
    currentTraktEpisodeMappingKey = currentEpisodeMappingCacheKey()
}

private fun PlayerRuntimeController.clearTraktEpisodeMapping() {
    currentTraktEpisodeMapping = null
    currentTraktEpisodeMappingKey = null
}
