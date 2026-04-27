package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPlanTraceTest {
    @Test
    fun `run emits provider_plan event with steps before execution`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val runner = ProviderPlanRunner(adapters = emptySet(), traceEvents = events)

        val route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tt14403178",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.PROVIDER_NATIVE_DIRECT,
            sourceContext = MetadataSourceContext(itemType = "series"),
            language = "en",
            seasonNumber = null,
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:399838"),
            targetIdRequiresIdentityResolution = false,
            trace = emptyList()
        )
        val plan = ProviderExecutionPlan(
            route = route,
            depth = MetadataDepth.DETAIL_CORE,
            steps = listOf(
                ProviderPlanStep(
                    apiShapeId = "tvdb.series.extended",
                    provider = MetadataPrimaryProvider.TVDB,
                    role = ProviderPlanRole.PRIMARY_CORE,
                    required = true
                )
            )
        )

        runCatching { runner.run(plan) }

        val planEvents = sink.events.filter { it.eventType == "metadata.provider_plan" }
        assertEquals(1, planEvents.size)
        val payload = planEvents.first().payload as Map<*, *>
        assertEquals("tt14403178", payload["contentId"])
        assertEquals("TVDB", payload["provider"])
        assertEquals("SERIES", payload["mediaKind"])
        assertEquals("DETAIL_CORE", payload["depth"])
        @Suppress("UNCHECKED_CAST")
        val steps = payload["steps"] as List<Map<String, Any?>>
        assertEquals(1, steps.size)
        assertEquals("tvdb.series.extended", steps.first()["apiShapeId"])
        assertEquals("tvdb.series.extended", steps.first()["stepId"])
        assertTrue(steps.first().containsKey("workClass"))
        assertTrue(steps.first().containsKey("cachePolicy"))
        assertEquals(false, steps.first()["requiresIdentityResolution"])
    }
}
