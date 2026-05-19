package com.nexio.tv.core.metadata.router

import com.nexio.tv.domain.model.HomeDisplayMetadata
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class ProviderPlanRunnerTest {
    @Test
    fun `provider plan steps are executed through mapped adapters`() = runTest {
        val step = ProviderPlanStep(
            apiShapeId = "tmdb.movie.core",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE,
            required = true
        )
        val route = route(provider = MetadataPrimaryProvider.TMDB)
        val runner = ProviderPlanRunner(
            adapters = setOf(FakeAdapter(step.apiShapeId, MetadataPrimaryProvider.TMDB))
        )

        val result = runner.run(
            ProviderExecutionPlan(route = route, depth = MetadataDepth.DETAIL_CORE, steps = listOf(step))
        )

        assertEquals(MetadataPrimaryProvider.TMDB, result.primaryCandidate.provider)
        assertEquals("tmdb.movie.core", result.stepResults.single().step.apiShapeId)
    }

    @Test
    fun `missing plan step adapter mapping fails test`() = runTest {
        val step = ProviderPlanStep(
            apiShapeId = "tmdb.movie.core",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE,
            required = true
        )
        val runner = ProviderPlanRunner(adapters = emptySet())

        try {
            runner.run(
                ProviderExecutionPlan(
                    route = route(provider = MetadataPrimaryProvider.TMDB),
                    depth = MetadataDepth.DETAIL_CORE,
                    steps = listOf(step)
                )
            )
            fail("Expected MissingPlanStepAdapter")
        } catch (_: MetadataRouteFailure.MissingPlanStepAdapter) {
            // Expected.
        }
    }

    @Test
    fun `provider plan runner selects narrow adapter over broad adapter for same step`() = runTest {
        val step = ProviderPlanStep(
            apiShapeId = "tmdb.movie.reviews",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.SECONDARY,
            required = true
        )
        val runner = ProviderPlanRunner(
            adapters = setOf(
                BroadEmptyAdapter(MetadataPrimaryProvider.TMDB),
                PrioritizedAdapter(
                    supportedShape = step.apiShapeId,
                    provider = MetadataPrimaryProvider.TMDB,
                    title = "Narrow Adapter"
                )
            )
        )

        val result = runner.run(
            ProviderExecutionPlan(
                route = route(provider = MetadataPrimaryProvider.TMDB),
                depth = MetadataDepth.DETAIL_SECONDARY,
                steps = listOf(step)
            )
        )

        assertEquals(
            "Narrow Adapter",
            result.stepResults.single().candidate?.fields?.get(ResolvedField.TITLE)?.value
        )
    }

    @Test
    fun `artwork step receives primary poster as fallback source context`() = runTest {
        val primaryStep = ProviderPlanStep(
            apiShapeId = "tmdb.movie.core",
            provider = MetadataPrimaryProvider.TMDB,
            role = ProviderPlanRole.PRIMARY_CORE,
            required = true
        )
        val artworkStep = ProviderPlanStep(
            apiShapeId = "rpdb.poster_template",
            provider = MetadataPrimaryProvider.RPDB,
            role = ProviderPlanRole.ARTWORK,
            required = false
        )
        val artworkAdapter = CapturingArtworkAdapter()
        val runner = ProviderPlanRunner(
            adapters = setOf(
                PosterPrimaryAdapter(primaryStep.apiShapeId),
                artworkAdapter
            )
        )

        runner.run(
            ProviderExecutionPlan(
                route = route(provider = MetadataPrimaryProvider.TMDB),
                depth = MetadataDepth.DETAIL_CORE,
                steps = listOf(primaryStep, artworkStep)
            )
        )

        assertEquals(
            "https://image.tmdb.org/t/p/w500/primary.jpg",
            artworkAdapter.capturedRoute?.sourceContext?.addonMetadata?.poster
        )
    }

    private fun route(provider: MetadataPrimaryProvider) = MetadataRoute(
        provider = provider,
        parentId = "tmdb:550",
        mediaKind = MetadataMediaKind.MOVIE,
        reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
        sourceContext = MetadataSourceContext(),
        targetIds = mapOf(provider to "550"),
        trace = emptyList()
    )

    private class FakeAdapter(
        private val supportedShape: String,
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId == supportedShape

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf(ResolvedField.TITLE to FieldValue("Adapter Title", FieldOwner.PRIMARY))
                )
            )
        }
    }

    private class BroadEmptyAdapter(
        override val provider: MetadataPrimaryProvider
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = true

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(provider = provider, fields = emptyMap())
            )
    }

    private class PrioritizedAdapter(
        private val supportedShape: String,
        override val provider: MetadataPrimaryProvider,
        private val title: String
    ) : MetadataProviderAdapter {
        override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId == supportedShape

        override fun priorityFor(step: ProviderPlanStep): Int = if (supports(step)) 100 else 0

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf(ResolvedField.TITLE to FieldValue(title, FieldOwner.PRIMARY))
                )
            )
    }

    private class PosterPrimaryAdapter(
        private val supportedShape: String
    ) : MetadataProviderAdapter {
        override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.TMDB

        override fun supports(step: ProviderPlanStep): Boolean = step.apiShapeId == supportedShape

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult =
            ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf(
                        ResolvedField.POSTER to FieldValue(
                            "https://image.tmdb.org/t/p/w500/primary.jpg",
                            FieldOwner.PRIMARY
                        )
                    )
                )
            )
    }

    private class CapturingArtworkAdapter : MetadataProviderAdapter {
        override val provider: MetadataPrimaryProvider = MetadataPrimaryProvider.RPDB
        var capturedRoute: MetadataRoute? = null

        override fun supports(step: ProviderPlanStep): Boolean = step.provider == provider

        override suspend fun execute(route: MetadataRoute, step: ProviderPlanStep): ProviderStepResult {
            capturedRoute = route
            return ProviderStepResult(
                step = step,
                candidate = MetadataCandidate(
                    provider = provider,
                    fields = mapOf(
                        ResolvedField.POSTER to FieldValue(
                            route.sourceContext.addonMetadata ?: HomeDisplayMetadata(),
                            FieldOwner.ARTWORK,
                            SourceRole.ARTWORK
                        )
                    )
                )
            )
        }
    }
}
