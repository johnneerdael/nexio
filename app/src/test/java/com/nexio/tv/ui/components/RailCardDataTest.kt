package com.nexio.tv.ui.components

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailCardDataTest {

    private fun makeRef(value: String) = ArtworkDisplayRef.LegacyString(
        value = value,
        imageType = ArtworkType.POSTER,
        trace = ArtworkTrace.empty()
    )

    private val sample = object : RailCardData {
        override val id: String = "tt12345"
        override val name: String? = "Some Title"
        override val posterRef: ArtworkDisplayRef? = makeRef("https://example.com/poster.jpg")
        override val posterProviderTag: String? = "tmdb"
    }

    @Test
    fun `interface exposes id`() {
        assertEquals("tt12345", sample.id)
    }

    @Test
    fun `interface exposes name as nullable`() {
        assertEquals("Some Title", sample.name)
    }

    @Test
    fun `interface exposes posterRef as nullable ArtworkDisplayRef`() {
        val ref = sample.posterRef
        assertEquals(ArtworkType.POSTER, ref?.imageType)
    }

    @Test
    fun `interface exposes posterProviderTag as nullable`() {
        assertEquals("tmdb", sample.posterProviderTag)
    }

    @Test
    fun `null fields are permitted`() {
        val empty = object : RailCardData {
            override val id: String = "x"
            override val name: String? = null
            override val posterRef: ArtworkDisplayRef? = null
            override val posterProviderTag: String? = null
        }
        assertNull(empty.name)
        assertNull(empty.posterRef)
        assertNull(empty.posterProviderTag)
    }

    @Test
    fun `adapter maps id and name from MetaPreview`() {
        val meta = makeMeta(id = "tt99", name = "Title", poster = "https://x/p.jpg")
        val card = meta.toRailCardData()
        assertEquals("tt99", card.id)
        assertEquals("Title", card.name)
    }

    @Test
    fun `adapter prefers typed artwork poster over legacy String`() {
        val typedRef = makeRef("typed-url")
        val artwork = com.nexio.tv.core.artwork.ArtworkBundle(poster = typedRef)
        val meta = makeMeta(
            id = "tt1",
            name = "x",
            poster = "legacy-url",
            artwork = artwork
        )
        val card = meta.toRailCardData()
        val ref = card.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("typed-url", ref.value)
    }

    @Test
    fun `adapter falls back to legacy poster String when typed artwork is null`() {
        val meta = makeMeta(id = "tt2", name = "x", poster = "legacy-url", artwork = null)
        val card = meta.toRailCardData()
        val ref = card.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("legacy-url", ref.value)
        assertEquals(ArtworkType.POSTER, ref.imageType)
    }

    @Test
    fun `adapter returns null posterRef when both typed and legacy are null`() {
        val meta = makeMeta(id = "tt3", name = "x", poster = null, artwork = null)
        val card = meta.toRailCardData()
        assertNull(card.posterRef)
    }

    @Test
    fun `adapter returns null posterRef when legacy String is blank`() {
        val meta = makeMeta(id = "tt3b", name = "x", poster = "   ", artwork = null)
        val card = meta.toRailCardData()
        assertNull(card.posterRef)
    }

    @Test
    fun `adapter passes through posterProviderTag`() {
        val meta = makeMeta(id = "tt4", name = "x", poster = "u", posterProviderTag = "tmdb")
        val card = meta.toRailCardData()
        assertEquals("tmdb", card.posterProviderTag)
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String? = null,
        artwork: com.nexio.tv.core.artwork.ArtworkBundle? = null,
        posterProviderTag: String? = null
    ): com.nexio.tv.domain.model.MetaPreview = com.nexio.tv.domain.model.MetaPreview(
        id = id,
        type = com.nexio.tv.domain.model.ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = com.nexio.tv.domain.model.PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        artwork = artwork,
        posterProviderTag = posterProviderTag
    )
}
