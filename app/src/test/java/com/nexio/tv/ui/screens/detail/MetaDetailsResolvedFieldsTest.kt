package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.ResolvedDisplayItem
import com.nexio.tv.domain.model.TitleRating
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.model.TrailerDisplayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaDetailsResolvedFieldsTest {

    private fun resolvedItem(
        title: String? = "The Movie",
        posterUrl: String? = "https://rpdb.example/p.jpg",
        ratingValue: Double? = 8.4
    ): ResolvedDisplayItem = ResolvedDisplayItem(
        itemKey = "movie:tt12345",
        contentId = "tt12345",
        parentId = "tt12345",
        itemType = ContentType.MOVIE,
        mediaKind = MetadataMediaKind.MOVIE,
        canonicalProvider = "RPDB",
        canonicalId = "tt12345",
        imdbId = "tt12345",
        stableIds = ProviderIds(imdb = "tt12345"),
        display = ResolvedDisplayFields(
            title = title,
            originalTitle = null,
            year = 2024,
            releaseDate = "2024-03-15",
            overview = "A story",
            genres = listOf("Drama"),
            runtimeText = "142 min",
            tomatoesRating = null
        ),
        artwork = ArtworkBundle(
            poster = posterUrl?.let {
                ArtworkDisplayRef.LegacyString(
                    value = it,
                    imageType = ArtworkType.POSTER,
                    trace = ArtworkTrace.empty()
                )
            },
            backdrop = null,
            logo = null,
            thumbnail = null
        ),
        rating = ratingValue?.let { TitleRating(it, TitleRatingSource.IMDB) },
        trailer = TrailerDisplayState(),
        hydrationState = HydrationState.CANONICAL_READY,
        sourceTrace = emptyList(),
        updatedAtMs = 1_700_000_000_000L
    )

    @Test
    fun `from() projects title, overview, year, runtime, rating from ResolvedDisplayItem`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem())

        assertEquals("The Movie", fields.title)
        assertEquals("A story", fields.overview)
        assertEquals(2024, fields.year)
        assertEquals("142 min", fields.runtimeText)
        assertEquals(8.4, fields.rating?.value)
        assertEquals(TitleRatingSource.IMDB, fields.rating?.source)
    }

    @Test
    fun `from() projects poster artwork URL from ResolvedDisplayItem`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem())
        assertEquals("https://rpdb.example/p.jpg", fields.posterUrl)
    }

    @Test
    fun `from() returns null poster when artwork bundle has no poster`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem(posterUrl = null))
        assertNull(fields.posterUrl)
    }

    @Test
    fun `from() returns null title when display title is null`() {
        val fields = MetaDetailsResolvedFields.from(resolvedItem(title = null))
        assertNull(fields.title)
    }
}
