package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.integration.RecordingTraceSink
import com.nexio.tv.core.trace.TraceMetadataEvents
import com.nexio.tv.domain.model.ContentType
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouteDecisionTraceTest {
    @Test
    fun `kitsu prefix routes KITSU and emits route_decision`() = runTest {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })
        val router = MetadataRouter(
            normalizer = MetadataRequestNormalizer(),
            animeIdentityIndex = InMemoryAnimeIdentityIndex(),
            idMappingStore = InMemoryIdMappingStore(),
            traceEvents = events
        )

        router.route(
            MetadataRequest(
                contentId = "kitsu:7442",
                contentType = ContentType.SERIES,
                sourceContext = MetadataSourceContext(itemType = "series"),
                language = "en",
                seasonNumber = null,
                depth = MetadataDepth.DETAIL_CORE
            )
        )

        val decisions = sink.events.filter { it.eventType == "metadata.route_decision" }
        assertEquals(1, decisions.size)
        val payload = decisions.first().payload as Map<*, *>
        assertEquals("kitsu:7442", payload["contentId"])
        assertEquals("KITSU", payload["provider"])
        assertEquals("ANIME", payload["mediaKind"])
        @Suppress("UNCHECKED_CAST")
        val used = payload["usedInputs"] as List<String>
        assertTrue("usedInputs must contain item.id", used.contains("item.id"))
        @Suppress("UNCHECKED_CAST")
        val ignored = payload["ignoredInputs"] as List<String>
        assertTrue("ignoredInputs must mention catalog.type", ignored.contains("catalog.type"))
        assertTrue("ignoredInputs must mention addon.name", ignored.contains("addon.name"))
        assertTrue("ignoredInputs must mention genre", ignored.contains("genre"))
    }
}
