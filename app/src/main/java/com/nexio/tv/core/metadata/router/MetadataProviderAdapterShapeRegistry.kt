package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes

object MetadataProviderAdapterShapeRegistry {
    val all: Set<String> = setOf(
        TmdbApiShapes.MOVIE_CORE,
        TmdbApiShapes.TV_CORE,
        TmdbApiShapes.SEASON_EPISODES,
        TmdbApiShapes.MOVIE_VIDEOS,
        TmdbApiShapes.TV_VIDEOS,
        TmdbApiShapes.MOVIE_REVIEWS,
        TmdbApiShapes.TV_REVIEWS,
        TmdbApiShapes.MOVIE_RECOMMENDATIONS,
        TmdbApiShapes.TV_RECOMMENDATIONS,
        TvdbApiShapes.SERIES_EXTENDED,
        TvdbApiShapes.SERIES_TRANSLATION,
        TvdbApiShapes.SERIES_EPISODES_SEASON_TYPE,
        TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
        TvdbApiShapes.EPISODE_TRANSLATION,
        KitsuApiShapes.ANIME_CORE,
        KitsuApiShapes.ANIME_EPISODES,
        KitsuApiShapes.CASTINGS,
        KitsuApiShapes.ANIME_STAFF,
        KitsuApiShapes.ANIME_PRODUCTIONS,
        KitsuApiShapes.MEDIA_RELATIONSHIPS
    )
}
