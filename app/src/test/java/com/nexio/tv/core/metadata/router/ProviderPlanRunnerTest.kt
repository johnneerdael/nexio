package com.nexio.tv.core.metadata.router

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
}
