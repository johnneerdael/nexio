package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.artwork.ArtworkDisplayRef
import com.nexio.tv.core.artwork.ArtworkTrace
import com.nexio.tv.core.artwork.ArtworkType
import com.nexio.tv.domain.model.HydrationState
import com.nexio.tv.ui.components.RailCardData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeRowItemRailCardDataTest {

    @Test
    fun `ModernHomeRowItem is a RailCardData`() {
        val item = sampleItem()
        assertTrue(item is RailCardData)
    }

    @Test
    fun `RailCardData id maps to contentId`() {
        val card: RailCardData = sampleItem(contentId = "tt42")
        assertEquals("tt42", card.id)
    }

    @Test
    fun `RailCardData name maps to title`() {
        val card: RailCardData = sampleItem(title = "Hello")
        assertEquals("Hello", card.name)
    }

    @Test
    fun `RailCardData posterProviderTag is null when posterRef is LegacyString`() {
        val ref = ArtworkDisplayRef.LegacyString(
            value = "x",
            imageType = ArtworkType.POSTER,
            trace = ArtworkTrace.empty()
        )
        val item = sampleItem(posterRef = ref)
        assertNull((item as RailCardData).posterProviderTag)
    }

    @Test
    fun `RailCardData posterProviderTag is null when posterRef is null`() {
        val item = sampleItem(posterRef = null)
        assertNull((item as RailCardData).posterProviderTag)
    }

    private fun sampleItem(
        contentId: String = "tt1",
        title: String? = "x",
        posterRef: ArtworkDisplayRef? = null
    ) = ModernHomeRowItem(
        itemKey = "movie:$contentId",
        contentId = contentId,
        parentId = contentId,
        title = title,
        year = null,
        posterRef = posterRef,
        backdropRef = null,
        logoRef = null,
        thumbnailRef = null,
        rating = null,
        hydrationState = HydrationState.PREVIEW_ONLY,
        posterProviderTag = posterRef.deriveProviderTag()
    )
}
