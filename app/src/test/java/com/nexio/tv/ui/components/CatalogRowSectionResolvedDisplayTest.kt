package com.nexio.tv.ui.components

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.ui.screens.home.ModernHomeRowItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRowSectionResolvedDisplayTest {
    @Test
    fun `overlay renders resolved visible fields and does not fall back to raw metadata`() {
        val raw = preview()
        val resolved = ModernHomeRowItem(
            itemKey = "movie:raw-id",
            contentId = "raw-id",
            parentId = "raw-id",
            title = "Resolved Title",
            year = 1999,
            posterRef = legacyRef("https://cdn.example/poster.jpg", ArtworkType.POSTER),
            backdropRef = null,
            logoRef = null,
            thumbnailRef = null,
            rating = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            runtime = null,
            tomatoesRating = null,
            hydrationState = HydrationState.CANONICAL_READY,
            posterProviderTag = null
        )

        val effective = overlayResolvedDisplay(raw, resolved)

        assertEquals("Resolved Title", effective.name)
        assertEquals("https://cdn.example/poster.jpg", effective.poster)
        assertNull(effective.background)
        assertNull(effective.logo)
        assertNull(effective.description)
        assertEquals(emptyList<String>(), effective.genres)
        assertNull(effective.releaseInfo)
        assertNull(effective.runtime)
        assertNull(effective.imdbRating)
        assertNull(effective.ratingSource)
        assertNull(effective.tomatoesRating)
    }

    @Test
    fun `overlay carries resolved secondary display fields`() {
        val raw = preview()
        val resolved = ModernHomeRowItem(
            itemKey = "movie:raw-id",
            contentId = "raw-id",
            parentId = "raw-id",
            title = "Resolved Title",
            year = 1999,
            posterRef = null,
            backdropRef = legacyRef("https://cdn.example/backdrop.jpg", ArtworkType.BACKDROP),
            logoRef = legacyRef("https://cdn.example/logo.png", ArtworkType.LOGO),
            thumbnailRef = null,
            rating = TitleRating(8.5, TitleRatingSource.TMDB),
            description = "Resolved overview",
            genres = listOf("Drama"),
            releaseInfo = "1999-10-15",
            runtime = "139m",
            tomatoesRating = 91.0,
            hydrationState = HydrationState.CANONICAL_READY,
            posterProviderTag = "tmdb"
        )

        val effective = overlayResolvedDisplay(raw, resolved)

        assertEquals("Resolved overview", effective.description)
        assertEquals(listOf("Drama"), effective.genres)
        assertEquals("1999-10-15", effective.releaseInfo)
        assertEquals("139m", effective.runtime)
        assertEquals(8.5f, effective.imdbRating ?: 0f, 0f)
        assertEquals(TitleRatingSource.TMDB, effective.ratingSource)
        assertEquals(91.0, effective.tomatoesRating ?: 0.0, 0.0)
        assertEquals("https://cdn.example/backdrop.jpg", effective.background)
        assertEquals("https://cdn.example/logo.png", effective.logo)
    }

    private fun preview() = MetaPreview(
        id = "raw-id",
        type = ContentType.MOVIE,
        name = "Raw Title",
        poster = "https://raw.example/poster.jpg",
        posterShape = PosterShape.POSTER,
        background = "https://raw.example/backdrop.jpg",
        logo = "https://raw.example/logo.png",
        description = "Raw overview",
        releaseInfo = "1998",
        runtime = "120m",
        imdbRating = 6.0f,
        ratingSource = TitleRatingSource.IMDB,
        tomatoesRating = 70.0,
        genres = listOf("Raw")
    )

    private fun legacyRef(value: String, type: ArtworkType): ArtworkDisplayRef =
        ArtworkDisplayRef.LegacyString(
            value = value,
            imageType = type,
            trace = ArtworkTrace.empty()
        )
}
