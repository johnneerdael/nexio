package com.nexio.tv.ui.screens.library

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryRailItemTest {

    @Test
    fun `LibraryRailItem implements RailCardData`() {
        val item = LibraryRailItem.fromEntry(makeEntry(id = "tt1", name = "X"))
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to LibraryEntry id`() {
        val item: RailCardData = LibraryRailItem.fromEntry(makeEntry(id = "tt42", name = "x"))
        assertEquals("tt42", item.id)
    }

    @Test
    fun `RailCardData name maps to LibraryEntry name`() {
        val item: RailCardData = LibraryRailItem.fromEntry(makeEntry(id = "tt1", name = "Hello"))
        assertEquals("Hello", item.name)
    }

    @Test
    fun `fromEntry wraps poster as LegacyString of POSTER type`() {
        val item = LibraryRailItem.fromEntry(
            makeEntry(id = "tt1", name = "x", poster = "https://x/p.jpg")
        )
        val ref = item.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/p.jpg", ref.value)
        assertEquals(ArtworkType.POSTER, ref.imageType)
    }

    @Test
    fun `posterRef is null when poster is null or blank`() {
        val nullCase = LibraryRailItem.fromEntry(makeEntry(id = "tt1", name = "x", poster = null))
        val blankCase = LibraryRailItem.fromEntry(makeEntry(id = "tt1", name = "x", poster = "  "))
        assertNull(nullCase.posterRef)
        assertNull(blankCase.posterRef)
    }

    @Test
    fun `posterProviderTag is null - LibraryEntry has no provider tag`() {
        val card: RailCardData = LibraryRailItem.fromEntry(makeEntry(id = "tt1", name = "x"))
        assertNull(card.posterProviderTag)
    }

    @Test
    fun `source LibraryEntry is retained`() {
        val entry = makeEntry(id = "tt1", name = "x")
        val item = LibraryRailItem.fromEntry(entry)
        assertEquals(entry, item.source)
    }

    private fun makeEntry(
        id: String,
        name: String,
        poster: String? = "https://x/p.jpg"
    ) = LibraryEntry(
        id = id,
        type = "movie",
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null
    )
}
