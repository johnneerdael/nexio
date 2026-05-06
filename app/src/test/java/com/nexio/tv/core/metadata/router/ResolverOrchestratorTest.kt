package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolverOrchestratorTest {
    private val orchestrator = ResolverOrchestrator()

    @Test
    fun `preview keeps only addon display local resolver and no network resolvers`() {
        val schedule = orchestrator.schedule(MetadataDepth.PREVIEW)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY),
            schedule.localResolvers
        )
        assertEquals("network", emptyList<ResolverType>(), schedule.networkResolvers)
    }

    @Test
    fun `detail core schedules rating and artwork locally`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_CORE)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY, ResolverType.RATING, ResolverType.ARTWORK),
            schedule.localResolvers
        )
        assertEquals("network", emptyList<ResolverType>(), schedule.networkResolvers)
    }

    @Test
    fun `detail media schedules trailers over network`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_MEDIA)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY),
            schedule.localResolvers
        )
        assertEquals(
            "network",
            listOf(ResolverType.TRAILERS),
            schedule.networkResolvers
        )
    }

    @Test
    fun `detail secondary schedules rating and artwork locally and reviews recommendations and organization person over network`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_SECONDARY)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY, ResolverType.RATING, ResolverType.ARTWORK),
            schedule.localResolvers
        )
        assertEquals(
            "network",
            listOf(
                ResolverType.REVIEWS,
                ResolverType.RECOMMENDATIONS,
                ResolverType.ORGANIZATION_PERSON
            ),
            schedule.networkResolvers
        )
    }

    @Test
    fun `season schedules rating locally`() {
        val schedule = orchestrator.schedule(MetadataDepth.SEASON)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY, ResolverType.RATING),
            schedule.localResolvers
        )
        assertEquals("network", emptyList<ResolverType>(), schedule.networkResolvers)
    }

    @Test
    fun `player schedules tracking over network only`() {
        val schedule = orchestrator.schedule(MetadataDepth.PLAYER)

        assertEquals(
            "local",
            listOf(ResolverType.ADDON_DISPLAY),
            schedule.localResolvers
        )
        assertEquals(
            "network",
            listOf(ResolverType.TRACKING),
            schedule.networkResolvers
        )
    }

    @Test
    fun `PLAYER does not schedule SKIP_SEGMENTS (F-12-01)`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val orchestrator = ResolverOrchestrator(events)
        val schedule = orchestrator.schedule(MetadataDepth.PLAYER)
        // After removal of the enum value, ResolverType.SKIP_SEGMENTS does not exist.
        // We assert no resolver type with that name is in the schedule.
        assertTrue(
            "PLAYER schedule must not contain SKIP_SEGMENTS (player skip is owned by SkipSegmentResolver)",
            (schedule.localResolvers + schedule.networkResolvers).none { it.name == "SKIP_SEGMENTS" }
        )
    }

    @Test
    fun `schedules are distinct`() {
        val schedules = listOf(
            MetadataDepth.PREVIEW,
            MetadataDepth.DETAIL_CORE,
            MetadataDepth.DETAIL_MEDIA,
            MetadataDepth.DETAIL_SECONDARY,
            MetadataDepth.SEASON,
            MetadataDepth.PLAYER
        ).associateWith(orchestrator::schedule)

        schedules.values.forEach { schedule ->
            assertTrue(schedule.localResolvers == schedule.localResolvers.distinct())
            assertTrue(schedule.networkResolvers == schedule.networkResolvers.distinct())
        }
    }

    @Test
    fun `DETAIL_MEDIA does not schedule ARTWORK (F-04-04 pin)`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_MEDIA)
        assertFalse(
            "ARTWORK should be DETAIL_CORE only (collapsed into core response per F-04-04). Found in DETAIL_MEDIA: ${schedule.localResolvers + schedule.networkResolvers}",
            ResolverType.ARTWORK in (schedule.localResolvers + schedule.networkResolvers)
        )
    }

    @Test
    fun `DETAIL_CORE still schedules ARTWORK (regression guard)`() {
        val schedule = orchestrator.schedule(MetadataDepth.DETAIL_CORE)
        assertTrue(
            "ARTWORK must remain in DETAIL_CORE schedule (it's the canonical artwork pass)",
            ResolverType.ARTWORK in (schedule.localResolvers + schedule.networkResolvers)
        )
    }
}
