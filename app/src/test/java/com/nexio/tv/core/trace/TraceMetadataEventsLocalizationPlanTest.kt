package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.RecordingTraceSink
import org.junit.Assert.assertEquals
import org.junit.Test

class TraceMetadataEventsLocalizationPlanTest {

    @Test
    fun `emitLocalizationPlan emits envelope with policy + counter payload`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { "s1" })

        events.emitLocalizationPlan(
            contentId = "tvdb:73244",
            provider = "TVDB",
            policyVersion = 2,
            requestedLanguage = "nld",
            fallbackLanguage = "eng",
            requestedIsFallback = false,
            allowProviderFallbackForMissingLocalizedFields = true,
            perEpisodeFallbacksAttempted = 3,
            perEpisodeFallbacksAllowed = 8
        )

        assertEquals(1, sink.events.size)
        val envelope = sink.events.first()
        assertEquals("metadata.localization_plan", envelope.eventType)
        val payload = envelope.payload as Map<*, *>
        assertEquals("tvdb:73244", payload["contentId"])
        assertEquals("TVDB", payload["provider"])
        assertEquals(2, payload["policyVersion"])
        assertEquals("nld", payload["requestedLanguage"])
        assertEquals("eng", payload["fallbackLanguage"])
        assertEquals(false, payload["requestedIsFallback"])
        assertEquals(true, payload["allowProviderFallbackForMissingLocalizedFields"])
        assertEquals(3, payload["perEpisodeFallbacksAttempted"])
        assertEquals(8, payload["perEpisodeFallbacksAllowed"])
    }

    @Test
    fun `emitLocalizationPlan is no-op when sessionId returns null`() {
        val sink = RecordingTraceSink()
        val events = TraceMetadataEvents(sink, sessionId = { null })

        events.emitLocalizationPlan(
            contentId = "tvdb:73244",
            provider = "TVDB",
            policyVersion = 2,
            requestedLanguage = "nld",
            fallbackLanguage = "eng",
            requestedIsFallback = false,
            allowProviderFallbackForMissingLocalizedFields = true,
            perEpisodeFallbacksAttempted = 0,
            perEpisodeFallbacksAllowed = 8
        )

        assertEquals(0, sink.events.size)
    }
}
