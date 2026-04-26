package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class MetadataRouterFacadeTest {
    @Test
    fun `preview requests bypass route and provider plan`() = runTest {
        val addonMetadata = HomeDisplayMetadata(title = "Addon title")
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(addonMetadata = addonMetadata),
                depth = MetadataDepth.PREVIEW
            )
        )

        assertNull(result.route)
        assertNull(result.plan)
        assertEquals(MetadataDepth.PREVIEW, result.resolverSchedule.depth)
        assertSame(addonMetadata, result.displayMetadata)
    }

    @Test
    fun `detail requests route build provider plan and keep addon display as initial metadata`() = runTest {
        val addonMetadata = HomeDisplayMetadata(title = "Addon title")
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tvdb:123",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(addonMetadata = addonMetadata),
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertEquals(MetadataDepth.DETAIL_CORE, result.plan?.depth)
        assertEquals(MetadataDepth.DETAIL_CORE, result.resolverSchedule.depth)
        assertSame(addonMetadata, result.displayMetadata)
    }

    @Test
    fun `player requests route and return route only provider plan`() = runTest {
        val result = facade().resolveRequest(
            MetadataRequest(
                contentId = "tmdb:550",
                contentType = ContentType.MOVIE,
                sourceContext = MetadataSourceContext(),
                depth = MetadataDepth.PLAYER
            )
        )

        assertEquals(MetadataPrimaryProvider.TMDB, result.route?.provider)
        assertEquals(MetadataDepth.PLAYER, result.plan?.depth)
        assertEquals(emptyList<ProviderPlanStep>(), result.plan?.steps)
        assertEquals(MetadataDepth.PLAYER, result.resolverSchedule.depth)
    }

    private fun facade(): MetadataRouterFacade =
        MetadataRouterFacade(
            router = MetadataRouter(
                normalizer = MetadataRequestNormalizer(),
                animeIdentityIndex = InMemoryAnimeIdentityIndex(),
                idMappingStore = InMemoryIdMappingStore()
            ),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator()
        )
}
