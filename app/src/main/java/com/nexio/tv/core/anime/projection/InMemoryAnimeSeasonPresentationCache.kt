package com.nexio.tv.core.anime.projection

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryAnimeSeasonPresentationCache @Inject constructor() : AnimeSeasonPresentationCache {

    private data class Key(val groupKey: AnimeWorkGroupKey, val sourceKitsuId: String)

    private val cache = ConcurrentHashMap<Key, AnimeSeasonPresentation>()

    override fun get(groupKey: AnimeWorkGroupKey, sourceKitsuId: String): AnimeSeasonPresentation? =
        cache[Key(groupKey, sourceKitsuId)]

    override fun put(groupKey: AnimeWorkGroupKey, sourceKitsuId: String, presentation: AnimeSeasonPresentation) {
        cache[Key(groupKey, sourceKitsuId)] = presentation
    }

    override fun invalidate(groupKey: AnimeWorkGroupKey) {
        cache.keys.removeIf { it.groupKey == groupKey }
    }
}
