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
        TmdbApiShapes.SEASON_VIDEOS,
        TmdbApiShapes.MOVIE_REVIEWS,
        TmdbApiShapes.TV_REVIEWS,
        TmdbApiShapes.MOVIE_RECOMMENDATIONS,
        TmdbApiShapes.TV_RECOMMENDATIONS,
        TmdbApiShapes.PERSON_DETAIL,
        TmdbApiShapes.PERSON_COMBINED_CREDITS,
        TmdbApiShapes.PERSON_FIND_BY_NAME,
        TmdbApiShapes.COMPANY_DETAIL,
        TmdbApiShapes.NETWORK_DETAIL,
        TmdbApiShapes.COMPANY_FIND_BY_NAME,
        TvdbApiShapes.SERIES_EXTENDED,
        TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
        TvdbApiShapes.TV_TRAILERS,
        KitsuApiShapes.ANIME_CORE,
        KitsuApiShapes.ANIME_EPISODES,
        KitsuApiShapes.CASTINGS,
        KitsuApiShapes.ANIME_STAFF,
        KitsuApiShapes.ANIME_PRODUCTIONS,
        KitsuApiShapes.MEDIA_RELATIONSHIPS
    )
}
