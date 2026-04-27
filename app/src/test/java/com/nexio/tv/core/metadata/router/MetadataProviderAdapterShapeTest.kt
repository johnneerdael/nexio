package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.KitsuApiShapes
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.integration.TvdbApiShapes
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataProviderAdapterShapeTest {
    @Test
    fun `all provider plan shapes have adapter mappings`() {
        val mappedShapes = MetadataProviderAdapterShapeRegistry.all
        val required = setOf(
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
            TvdbApiShapes.SERIES_EPISODES_LANGUAGE,
            KitsuApiShapes.ANIME_CORE,
            KitsuApiShapes.ANIME_EPISODES,
            KitsuApiShapes.CASTINGS,
            KitsuApiShapes.ANIME_STAFF,
            KitsuApiShapes.ANIME_PRODUCTIONS,
            KitsuApiShapes.MEDIA_RELATIONSHIPS
        )

        assertTrue("Missing adapter mappings: ${required - mappedShapes}", mappedShapes.containsAll(required))
    }
}
