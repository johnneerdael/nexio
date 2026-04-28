package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.SourcePayloadQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktRailPreviewMapperTest {
    @Test
    fun `trakt trending show maps title year ids and watchers without artwork`() {
        val preview = TraktRailPreviewMapper().mapTrendingShow(
            railId = "trakt_trending_shows",
            item = TraktTrendingShowItemDto(
                watchers = 541,
                show = TraktShowDto(
                    title = "Breaking Bad",
                    year = 2008,
                    ids = TraktIdsDto(
                        trakt = 1,
                        slug = "breaking-bad",
                        imdb = "tt0903747",
                        tmdb = 1396,
                        tvdb = 81189
                    )
                )
            ),
            position = 0,
            generatedAtMs = 1_000L
        )!!

        assertEquals(RailSource.BUILT_IN_TRAKT, preview.railSource)
        assertEquals(ProviderId.TRAKT, preview.sourceProvider)
        assertEquals("trakt:show:1", preview.sourceItemId)
        assertEquals(ContentType.SERIES, preview.itemType)
        assertEquals("tt0903747", preview.stableIds.imdb)
        assertEquals("1396", preview.stableIds.tmdb)
        assertEquals("81189", preview.stableIds.tvdb)
        assertEquals("1", preview.stableIds.trakt)
        assertEquals("breaking-bad", preview.stableIds.slug)
        assertEquals("Breaking Bad", preview.display.title)
        assertEquals(2008, preview.display.year)
        assertNull(preview.display.posterUrl)
        assertEquals(541, preview.ranking?.watchers)
        assertEquals(1, preview.ranking?.rank)
        assertEquals(SourcePayloadQuality.SPARSE_IDENTITY, preview.sourcePayloadQuality)
    }

    @Test
    fun `trakt trending movie maps as movie with movie source id`() {
        val preview = TraktRailPreviewMapper().mapTrendingMovie(
            railId = "trakt_trending_movies",
            item = TraktTrendingMovieItemDto(
                watchers = 88,
                movie = TraktMovieDto(
                    title = "Heat",
                    year = 1995,
                    ids = TraktIdsDto(
                        trakt = 6,
                        slug = "heat-1995",
                        imdb = "tt0113277",
                        tmdb = 949
                    )
                )
            ),
            position = 3,
            generatedAtMs = 2_000L
        )!!

        assertEquals(RailSource.BUILT_IN_TRAKT, preview.railSource)
        assertEquals(ProviderId.TRAKT, preview.sourceProvider)
        assertEquals("trakt:movie:6", preview.sourceItemId)
        assertEquals(ContentType.MOVIE, preview.itemType)
        assertEquals("tt0113277", preview.stableIds.imdb)
        assertEquals("949", preview.stableIds.tmdb)
        assertEquals("6", preview.stableIds.trakt)
        assertEquals("heat-1995", preview.stableIds.slug)
        assertEquals("Heat", preview.display.title)
        assertEquals(1995, preview.display.year)
        assertNull(preview.display.posterUrl)
        assertEquals(88, preview.ranking?.watchers)
        assertEquals(4, preview.ranking?.rank)
        assertEquals(SourcePayloadQuality.SPARSE_IDENTITY, preview.sourcePayloadQuality)
    }

    @Test
    fun `missing trakt id returns null`() {
        val mapper = TraktRailPreviewMapper()

        assertNull(
            mapper.mapTrendingShow(
                railId = "trakt_trending_shows",
                item = TraktTrendingShowItemDto(
                    watchers = 541,
                    show = TraktShowDto(
                        title = "Breaking Bad",
                        year = 2008,
                        ids = TraktIdsDto(trakt = null)
                    )
                ),
                position = 0,
                generatedAtMs = 1_000L
            )
        )
        assertNull(
            mapper.mapTrendingMovie(
                railId = "trakt_trending_movies",
                item = TraktTrendingMovieItemDto(
                    watchers = 88,
                    movie = TraktMovieDto(
                        title = "Heat",
                        year = 1995,
                        ids = TraktIdsDto(trakt = null)
                    )
                ),
                position = 0,
                generatedAtMs = 1_000L
            )
        )
    }
}
