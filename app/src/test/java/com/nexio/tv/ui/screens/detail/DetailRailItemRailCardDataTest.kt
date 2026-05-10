package com.nexio.tv.ui.screens.detail

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailRailItemRailCardDataTest {

    @Test
    fun `DetailRailItem is a RailCardData`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt1", name = "X"))
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to contentId`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt42", name = "x"))
        val card: RailCardData = item
        assertEquals("tt42", card.id)
    }

    @Test
    fun `RailCardData name maps to title`() {
        val item = DetailRailItem.fromMetaPreview(makeMeta(id = "tt1", name = "Hello"))
        val card: RailCardData = item
        assertEquals("Hello", card.name)
    }

    @Test
    fun `RailCardData posterProviderTag passes through from source`() {
        val item = DetailRailItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", posterProviderTag = "tvdb")
        )
        val card: RailCardData = item
        assertEquals("tvdb", card.posterProviderTag)
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
