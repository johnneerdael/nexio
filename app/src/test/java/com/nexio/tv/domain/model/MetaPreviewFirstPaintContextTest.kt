package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetaPreviewFirstPaintContextTest {
    @Test
    fun `addon previews default to addon first paint source and no rail context`() {
        val preview = metaPreview()

        assertEquals(FirstPaintSource.ADDON_META_PREVIEW, preview.firstPaintSource)
        assertNull(preview.firstPaintSourceProvider)
        assertEquals(ProviderIds(), preview.firstPaintStableIds)
        assertNull(preview.firstPaintRailSource)
        assertNull(preview.firstPaintSourceItemId)
    }

    @Test
    fun `rail previews can carry source provider stable ids rail source and source item id`() {
        val preview = metaPreview().copy(
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TRAKT,
            firstPaintStableIds = ProviderIds(trakt = "1", tvdb = "81189", tmdb = "1396"),
            firstPaintRailSource = RailSource.BUILT_IN_TRAKT,
            firstPaintSourceItemId = "trakt:show:1"
        )

        assertEquals(FirstPaintSource.RAIL_PREVIEW, preview.firstPaintSource)
        assertEquals(ProviderId.TRAKT, preview.firstPaintSourceProvider)
        assertEquals("1", preview.firstPaintStableIds.trakt)
        assertEquals("81189", preview.firstPaintStableIds.tvdb)
        assertEquals("1396", preview.firstPaintStableIds.tmdb)
        assertEquals(RailSource.BUILT_IN_TRAKT, preview.firstPaintRailSource)
        assertEquals("trakt:show:1", preview.firstPaintSourceItemId)
    }

    private fun metaPreview(): MetaPreview = MetaPreview(
        id = "tt0903747",
        type = ContentType.SERIES,
        name = "Breaking Bad",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2008",
        imdbRating = null,
        genres = emptyList()
    )
}
