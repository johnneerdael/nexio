package com.nexio.tv.core.anime.projection

interface AnimeEpisodeCoordinateStore {
    fun get(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
    ): AnimeEpisodeProjection?

    fun put(
        groupKey: AnimeWorkGroupKey,
        source: SourceEpisodeCoordinate,
        target: EpisodeProjectionTarget,
        projection: AnimeEpisodeProjection,
    )

    fun invalidate(groupKey: AnimeWorkGroupKey)
}
