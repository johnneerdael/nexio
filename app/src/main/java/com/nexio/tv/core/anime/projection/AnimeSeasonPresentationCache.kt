package com.nexio.tv.core.anime.projection

interface AnimeSeasonPresentationCache {
    fun get(groupKey: AnimeWorkGroupKey, sourceKitsuId: String): AnimeSeasonPresentation?
    fun put(groupKey: AnimeWorkGroupKey, sourceKitsuId: String, presentation: AnimeSeasonPresentation)
    fun invalidate(groupKey: AnimeWorkGroupKey)
}
