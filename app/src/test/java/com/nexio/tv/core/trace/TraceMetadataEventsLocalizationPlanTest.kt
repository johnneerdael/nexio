package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceMetadataEventsLocalizationPlanTest {
    @Test
    fun `emitLocalizationPlan emits envelope with payloads`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitLocalizationPlan(
            provider = "TVDB",
            contentId = "tvdb:399838",
            requestedLanguage = "nl",
            fallbackLanguage = "en",
            policyVersion = 1,
            providerFallbackAllowedForMissingLocalizedFields = false,
            payloads = listOf(
                mapOf("apiShapeId" to "tvdb.series.episodes.language", "language" to "en",
                      "cacheDecision" to "HIT", "executedNetwork" to false),
                mapOf("apiShapeId" to "tvdb.series.episodes.language", "language" to "nl",
                      "cacheDecision" to "MISS_THEN_NETWORK", "executedNetwork" to true)
            ),
            perEpisodeTranslationFallbacks = mapOf("attempted" to 2, "maxAllowed" to 8)
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.first()
        assertEquals("metadata.localization_plan", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("TVDB", payload["provider"])
        assertEquals("nl", payload["requestedLanguage"])
        assertEquals("en", payload["fallbackLanguage"])
        @Suppress("UNCHECKED_CAST")
        val payloads = payload["payloads"] as List<Map<String, Any?>>
        assertEquals(2, payloads.size)
        assertEquals("HIT", payloads[0]["cacheDecision"])
        assertTrue(payload["perEpisodeTranslationFallbacks"] is Map<*, *>)
    }
}
