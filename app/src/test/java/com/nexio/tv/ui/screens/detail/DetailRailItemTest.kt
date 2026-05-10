package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.TitleRatingSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRailItemTest {

    @Test
    fun `fromMetaPreview maps fields and wraps artwork as LegacyString`() {
        val meta = makeMeta(
            id = "tt0137523",
            name = "Fight Club",
            poster = "https://image.tmdb.org/poster.jpg",
            background = "https://image.tmdb.org/backdrop.jpg",
            logo = "https://image.tmdb.org/logo.png",
            releaseInfo = "1999-10-15",
            imdbRating = 8.8f,
            ratingSource = TitleRatingSource.IMDB
        )

        val item = DetailRailItem.fromMetaPreview(meta)

        assertEquals("tt0137523", item.contentId)
        assertEquals("Fight Club", item.title)
        assertEquals(1999, item.year)

        val poster = item.posterRef
        assertTrue("poster ref must be LegacyString", poster is ArtworkDisplayRef.LegacyString)
        poster as ArtworkDisplayRef.LegacyString
        assertEquals("https://image.tmdb.org/poster.jpg", poster.value)
        assertEquals(ArtworkType.POSTER, poster.imageType)

        val backdrop = item.backdropRef
        assertTrue(backdrop is ArtworkDisplayRef.LegacyString)
        backdrop as ArtworkDisplayRef.LegacyString
        assertEquals("https://image.tmdb.org/backdrop.jpg", backdrop.value)
        assertEquals(ArtworkType.BACKDROP, backdrop.imageType)

        val logo = item.logoRef
        assertTrue(logo is ArtworkDisplayRef.LegacyString)
        logo as ArtworkDisplayRef.LegacyString
        assertEquals("https://image.tmdb.org/logo.png", logo.value)
        assertEquals(ArtworkType.LOGO, logo.imageType)

        assertNotNull(item.rating)
        assertEquals(8.8, item.rating!!.value, 0.001)
        assertEquals(TitleRatingSource.IMDB, item.rating!!.source)

        // itemKey is non-blank and includes the contentId
        assertTrue("itemKey carries contentId", item.itemKey.contains("tt0137523"))

        // source must be threaded unchanged (reference equality)
        assertSame(meta, item.source)
    }

    @Test
    fun `fromMetaPreview returns null artwork refs when source fields are null`() {
        val meta = makeMeta(
            id = "x:1",
            name = "Untitled",
            poster = null,
            background = null,
            logo = null,
            releaseInfo = null,
            imdbRating = null,
            ratingSource = null
        )

        val item = DetailRailItem.fromMetaPreview(meta)

        assertNull(item.posterRef)
        assertNull(item.backdropRef)
        assertNull(item.logoRef)
        assertNull(item.year)
        assertNull(item.rating)
    }

    @Test
    fun `fromMetaPreview returns null artwork refs when source fields are blank`() {
        val meta = makeMeta(
            id = "y:1",
            name = "Blanks",
            poster = "   ",
            background = "",
            logo = "  ",
            releaseInfo = "",
            imdbRating = null,
            ratingSource = null
        )

        val item = DetailRailItem.fromMetaPreview(meta)

        assertNull(item.posterRef)
        assertNull(item.backdropRef)
        assertNull(item.logoRef)
        // empty releaseInfo string -> first split token is "" -> toIntOrNull -> null
        assertNull(item.year)
    }

    @Test
    fun `fromMetaPreview defaults rating source to IMDB when meta lacks one`() {
        val meta = makeMeta(
            id = "z:1",
            name = "Default Source",
            poster = null,
            background = null,
            logo = null,
            releaseInfo = "2010",
            imdbRating = 7.2f,
            ratingSource = null
        )

        val item = DetailRailItem.fromMetaPreview(meta)

        assertNotNull(item.rating)
        assertEquals(7.2, item.rating!!.value, 0.001)
        assertEquals(TitleRatingSource.IMDB, item.rating!!.source)
        assertEquals(2010, item.year)
    }

    @Test
    fun `two fromMetaPreview calls on content-equal inputs produce content-equal items`() {
        fun build(): DetailRailItem = DetailRailItem.fromMetaPreview(
            makeMeta(
                id = "tt0137523",
                name = "Fight Club",
                poster = "https://image.tmdb.org/poster.jpg",
                background = "https://image.tmdb.org/backdrop.jpg",
                logo = "https://image.tmdb.org/logo.png",
                releaseInfo = "1999-10-15",
                imdbRating = 8.8f,
                ratingSource = TitleRatingSource.IMDB
            )
        )

        val a = build()
        val b = build()

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String?,
        background: String?,
        logo: String?,
        releaseInfo: String?,
        imdbRating: Float?,
        ratingSource: TitleRatingSource?
    ): MetaPreview = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = null,
        genres = emptyList(),
        releaseInfo = releaseInfo,
        runtime = null,
        imdbRating = imdbRating,
        ratingSource = ratingSource,
        tomatoesRating = null,
        trailerYtIds = emptyList(),
        language = null,
        posterProviderTag = null,
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = null,
        firstPaintStableIds = ProviderIds(),
        firstPaintRailSource = null,
        firstPaintSourceItemId = null,
        artwork = null
    )
}
