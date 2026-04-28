package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeMetadataSourceContextTest {
    @Test
    fun `addon previews default to addon preview metadata source context`() {
        val context = metaPreview().toHomeMetadataSourceContext()

        assertEquals("series", context.itemType)
        assertEquals(SourceRole.ADDON_PREVIEW, context.previewSourceRole)
        assertNull(context.previewSourceProvider)
        assertEquals(ProviderIds(), context.previewStableIds)
        assertNull(context.previewSourceItemId)
        assertNull(context.previewRailSource)
        assertEquals("Breaking Bad", context.addonMetadata?.title)
    }

    @Test
    fun `rail previews carry provider stable ids source item and rail source`() {
        val stableIds = ProviderIds(trakt = "1", imdb = "tt0903747", tmdb = "1396", tvdb = "81189")
        val context = metaPreview().copy(
            firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
            firstPaintSourceProvider = ProviderId.TRAKT,
            firstPaintStableIds = stableIds,
            firstPaintSourceItemId = "trakt:show:1",
            firstPaintRailSource = RailSource.BUILT_IN_TRAKT
        ).toHomeMetadataSourceContext()

        assertEquals("series", context.itemType)
        assertEquals(SourceRole.RAIL_PREVIEW, context.previewSourceRole)
        assertEquals(ProviderId.TRAKT.name, context.previewSourceProvider)
        assertEquals(stableIds, context.previewStableIds)
        assertEquals("trakt:show:1", context.previewSourceItemId)
        assertEquals(RailSource.BUILT_IN_TRAKT.name, context.previewRailSource)
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
