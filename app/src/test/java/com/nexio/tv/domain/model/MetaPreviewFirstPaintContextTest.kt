package com.nexio.tv.domain.model

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.metadata.router.InMemoryAnimeIdentityIndex
import com.nexio.tv.core.metadata.router.InMemoryIdMappingStore
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRequestNormalizer
import com.nexio.tv.core.metadata.router.MetadataRouter
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.ui.screens.home.toHomeMetadataSourceContext
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `addon preview stable ids enter shared source context and seed metadata router targets`() = runTest {
        val preview = metaPreview().copy(
            id = "stremio-addon-item",
            firstPaintStableIds = ProviderIds(
                imdb = "tt0903747",
                tmdb = "1396",
                tvdb = "81189"
            ),
            firstPaintSourceItemId = "addon:top-streaming:series:stremio-addon-item"
        )
        val sourceContext = preview.toHomeMetadataSourceContext()

        assertEquals(SourceRole.ADDON_PREVIEW, sourceContext.previewSourceRole)
        assertEquals(preview.firstPaintStableIds, sourceContext.previewStableIds)
        assertEquals("addon:top-streaming:series:stremio-addon-item", sourceContext.previewSourceItemId)

        val route = MetadataRouter(
            normalizer = MetadataRequestNormalizer(
                traceEvents = TraceMetadataEvents(RecordingTraceSink()) { null }
            ),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore()
        ).route(
            MetadataRequest(
                contentId = preview.id,
                contentType = preview.type,
                sourceContext = sourceContext,
                language = "en",
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(SourceRole.ADDON_PREVIEW, route.sourceContext.previewSourceRole)
        assertEquals("tt0903747", route.targetIds[MetadataPrimaryProvider.IMDB])
        assertEquals("tmdb:1396", route.targetIds[MetadataPrimaryProvider.TMDB])
        assertEquals("tvdb:81189", route.targetIds[MetadataPrimaryProvider.TVDB])
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
