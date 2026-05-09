package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.DisplaySourceRank
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SlotConversionsTest {
    @Test
    fun `MetaPreview converts to FIRST_PAINT slots with rail provenance`() {
        val item = MetaPreview(
            id = "tt0137523",
            type = ContentType.MOVIE,
            name = "Fight Club",
            poster = "https://image.tmdb.org/poster.jpg",
            posterShape = PosterShape.POSTER,
            background = "https://image.tmdb.org/backdrop.jpg",
            logo = null,
            description = "A description",
            genres = listOf("Drama"),
            releaseInfo = "1999",
            runtime = "139 min",
            imdbRating = 8.8f,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = "en",
            posterProviderTag = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(imdb = "tt0137523"),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "tt0137523",
            artwork = null
        )

        val slots = item.toFirstPaintSlots(nowMs = 1_000L)

        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.title.rank)
        assertEquals("Fight Club", slots.title.value)
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.poster.rank)
        assertNotNull(slots.poster.value)
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.backdrop.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.logo.rank)
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.rating.rank)
        assertEquals(8.8, slots.rating.value!!.value, 0.001)
    }

    @Test
    fun `MetaPreview with null optional fields yields EMPTY slots for those fields`() {
        val item = MetaPreview(
            id = "x:1",
            type = ContentType.MOVIE,
            name = "Untitled",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            imdbRating = null,
            ratingSource = null,
            tomatoesRating = null,
            trailerYtIds = emptyList(),
            language = null,
            posterProviderTag = null,
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = null,
            firstPaintStableIds = ProviderIds(),
            firstPaintRailSource = null,
            firstPaintSourceItemId = "x:1",
            artwork = null
        )

        val slots = item.toFirstPaintSlots(nowMs = 1_000L)

        // name is non-null so title is FIRST_PAINT
        assertEquals(DisplaySourceRank.FIRST_PAINT, slots.title.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.poster.rank)
        assertEquals(DisplaySourceRank.EMPTY, slots.rating.rank)
    }
}
