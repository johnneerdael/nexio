package com.nexio.tv.ui.screens.search

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultItemTest {

    @Test
    fun `SearchResultItem implements RailCardData`() {
        val item = SearchResultItem.fromMetaPreview(makeMeta(id = "tt1", name = "X"))
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to contentId`() {
        val item = SearchResultItem.fromMetaPreview(makeMeta(id = "tt42", name = "x"))
        val card: RailCardData = item
        assertEquals("tt42", card.id)
    }

    @Test
    fun `RailCardData name maps to title`() {
        val item = SearchResultItem.fromMetaPreview(makeMeta(id = "tt1", name = "Hello"))
        val card: RailCardData = item
        assertEquals("Hello", card.name)
    }

    @Test
    fun `fromMetaPreview wraps poster as LegacyString of POSTER type`() {
        val item = SearchResultItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = "https://x/p.jpg")
        )
        val ref = item.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/p.jpg", ref.value)
        assertEquals(ArtworkType.POSTER, ref.imageType)
    }

    @Test
    fun `posterRef is null when poster is null or blank`() {
        val nullCase = SearchResultItem.fromMetaPreview(makeMeta(id = "tt1", name = "x", poster = null))
        val blankCase = SearchResultItem.fromMetaPreview(makeMeta(id = "tt1", name = "x", poster = "  "))
        assertNull(nullCase.posterRef)
        assertNull(blankCase.posterRef)
    }

    @Test
    fun `posterProviderTag passes through from source`() {
        val item = SearchResultItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", posterProviderTag = "tmdb")
        )
        val card: RailCardData = item
        assertEquals("tmdb", card.posterProviderTag)
    }

    @Test
    fun `source MetaPreview is retained`() {
        val meta = makeMeta(id = "tt1", name = "x")
        val item = SearchResultItem.fromMetaPreview(meta)
        assertEquals(meta, item.source)
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String? = "https://x/p.jpg",
        posterProviderTag: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = posterProviderTag
    )
}
