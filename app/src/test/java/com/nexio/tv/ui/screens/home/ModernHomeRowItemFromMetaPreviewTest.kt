package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.homeDisplayItemKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeRowItemFromMetaPreviewTest {

    @Test
    fun `fromMetaPreview maps id and title from MetaPreview`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt99", name = "Title")
        )
        assertEquals("tt99", item.contentId)
        assertEquals("tt99", item.parentId)
        assertEquals("Title", item.title)
        assertEquals(homeDisplayItemKey(ContentType.MOVIE.toApiString(), "tt99"), item.itemKey)
    }

    @Test
    fun `fromMetaPreview wraps poster URL as LegacyString of POSTER type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = "https://x/p.jpg")
        )
        val ref = item.posterRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/p.jpg", ref.value)
        assertEquals(ArtworkType.POSTER, ref.imageType)
    }

    @Test
    fun `fromMetaPreview wraps background URL as LegacyString of BACKDROP type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", background = "https://x/b.jpg")
        )
        val ref = item.backdropRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/b.jpg", ref.value)
        assertEquals(ArtworkType.BACKDROP, ref.imageType)
    }

    @Test
    fun `fromMetaPreview wraps logo URL as LegacyString of LOGO type`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", logo = "https://x/l.jpg")
        )
        val ref = item.logoRef as ArtworkDisplayRef.LegacyString
        assertEquals("https://x/l.jpg", ref.value)
        assertEquals(ArtworkType.LOGO, ref.imageType)
    }

    @Test
    fun `fromMetaPreview returns null poster ref when poster is null`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = null)
        )
        assertNull(item.posterRef)
    }

    @Test
    fun `fromMetaPreview returns null poster ref when poster is blank`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", poster = "  ")
        )
        assertNull(item.posterRef)
    }

    @Test
    fun `fromMetaPreview extracts year from releaseInfo`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", releaseInfo = "2024-03-15")
        )
        assertEquals(2024, item.year)
    }

    @Test
    fun `fromMetaPreview returns null year when releaseInfo is null`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", releaseInfo = null)
        )
        assertNull(item.year)
    }

    @Test
    fun `fromMetaPreview passes posterProviderTag through`() {
        val item = ModernHomeRowItem.fromMetaPreview(
            makeMeta(id = "tt1", name = "x", posterProviderTag = "tmdb")
        )
        assertEquals("tmdb", item.posterProviderTag)
    }

    @Test
    fun `fromMetaPreview sets hydrationState to PREVIEW_ONLY`() {
        val item = ModernHomeRowItem.fromMetaPreview(makeMeta(id = "tt1", name = "x"))
        assertEquals(HydrationState.PREVIEW_ONLY, item.hydrationState)
    }

    private fun makeMeta(
        id: String,
        name: String,
        poster: String? = null,
        background: String? = null,
        logo: String? = null,
        releaseInfo: String? = null,
        posterProviderTag: String? = null
    ) = MetaPreview(
        id = id,
        type = ContentType.MOVIE,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = null,
        genres = emptyList(),
        posterProviderTag = posterProviderTag
    )
}
