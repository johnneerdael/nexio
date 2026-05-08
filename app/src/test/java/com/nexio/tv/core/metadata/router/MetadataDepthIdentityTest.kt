package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.NoopRuntimeTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataDepthIdentityTest {
    @Test
    fun `identity depth is routable and schedules no field resolvers`() {
        val schedule = ResolverOrchestrator().schedule(MetadataDepth.IDENTITY)

        assertTrue(schedule.localResolvers.isEmpty())
        assertTrue(schedule.networkResolvers.isEmpty())
    }

    @Test
    fun `identity depth does not build provider execution steps`() {
        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tvdb:393268",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(itemType = "series"),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:393268"),
            targetIdRequiresIdentityResolution = true,
            trace = listOf(MetadataRouteTrace(MetadataDecisionReason.PROVIDER_NATIVE_DIRECT, "test"))
        )

        val plan = ProviderPlanExecutor().buildPlan(route, MetadataDepth.IDENTITY)

        assertEquals(MetadataDepth.IDENTITY, plan.depth)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun `identity request can be normalized by router`() = runTest {
        val events = TraceMetadataEvents(
            sink = NoopRuntimeTraceSink,
            sessionId = { null }
        )
        val router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(traceEvents = events),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = events
        )

        val route = router.route(
            MetadataRequest(
                contentId = " tvdb:393268:1:2 ",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                depth = MetadataDepth.IDENTITY
            )
        )

        assertEquals("tvdb:393268", route.parentId)
        assertEquals(MetadataPrimaryProvider.TVDB, route.provider)
    }

    @Test
    fun `identity facade request returns unresolved route with empty plan`() = runTest {
        val adapter = RecordingProviderAdapter(MetadataPrimaryProvider.TVDB)
        val facade = MetadataRouterFacade(
            router = router(),
            providerPlanExecutor = ProviderPlanExecutor(),
            resolverOrchestrator = ResolverOrchestrator(),
            identityResolver = MetadataIdentityResolver(
                lookup = object : MetadataIdentityResolver.Lookup {
                    override suspend fun tmdbToTvdb(tmdbId: String): String? = null
                    override suspend fun tvdbToTmdb(tvdbId: String): String? = null
                }
            ),
            providerPlanRunner = ProviderPlanRunner(setOf(adapter)),
            fieldResolver = FieldResolver()
        )

        val result = facade.resolveRequest(
            MetadataRequest(
                contentId = "tmdb:393268",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                depth = MetadataDepth.IDENTITY
            )
        )

        assertEquals(MetadataPrimaryProvider.TVDB, result.route?.provider)
        assertEquals("tmdb:393268", result.route?.parentId)
        assertTrue(result.route?.targetIdRequiresIdentityResolution == true)
        assertEquals(MetadataDepth.IDENTITY, result.plan?.depth)
        assertTrue(result.plan?.steps.orEmpty().isEmpty())
        assertTrue(result.providerRunResult?.stepResults.orEmpty().isEmpty())
        assertEquals(0, adapter.calls)
    }

    private fun router(): MetadataRouter {
        val events = TraceMetadataEvents(
            sink = NoopRuntimeTraceSink,
            sessionId = { null }
        )
        return MetadataRouter(
            normalizer = MetadataRequestNormalizer(traceEvents = events),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = events
        )
    }

    private class RecordingProviderAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        var calls = 0

        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            calls += 1
            return ProviderStepResult(step = step)
        }
    }
}
