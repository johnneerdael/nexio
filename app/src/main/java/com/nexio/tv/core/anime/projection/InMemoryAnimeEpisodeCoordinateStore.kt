package com.nexio.tv.core.anime.projection

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InMemoryAnimeEpisodeCoordinateStore @Inject constructor() : AnimeEpisodeCoordinateStore {
    private data class Key(
        val groupKey: AnimeWorkGroupKey,
        val sourceKitsuId: String,
        val season: Int,
        val episode: Int,
        val target: EpisodeProjectionTarget,
    )

    private val cache = ConcurrentHashMap<Key, AnimeEpisodeProjection>()

    override fun get(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ) = cache[Key(groupKey, source.sourceKitsuId, source.season, source.episode, target)]

    override fun put(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
        projection: AnimeEpisodeProjection,
    ) {
        cache[Key(groupKey, source.sourceKitsuId, source.season, source.episode, target)] = projection
    }

    override fun invalidate(groupKey: AnimeWorkGroupKey) {
        cache.keys.removeIf { it.groupKey == groupKey }
    }
}
