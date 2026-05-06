package com.nexio.tv.core.metadata.router

import com.nexio.tv.core.trace.TraceEventEnvelope
import com.nexio.tv.core.trace.TraceValidationRules
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataRouterIgnoresAddonIsAnimeTest {

    private fun envelope(eventType: String, sequence: Long, payload: Map<String, Any?>) =
        TraceEventEnvelope(
            traceSessionId = "s",
            sequence = sequence,
            wallClockMs = 1L,
            elapsedRealtimeMs = 1L,
            threadName = "t",
            eventType = eventType,
            payload = payload,
        )

    @Test
    fun `route decision used inputs never include addon isAnime token`() {
        val event = envelope(
            eventType = "metadata.route_decision",
            sequence = 1L,
            payload = mapOf("usedInputs" to listOf("item.id", "addon.isAnime")),
        )

        val failures = TraceValidationRules.RouteDecisionUsedInputs.apply(listOf(event))

        assertTrue(failures.isNotEmpty())
    }

    @Test
    fun `route decision used inputs allow normal item id and anime identity input`() {
        val event = envelope(
            eventType = "metadata.route_decision",
            sequence = 1L,
            payload = mapOf("usedInputs" to listOf("item.id", "AnimeIdentityIndex")),
        )

        val failures = TraceValidationRules.RouteDecisionUsedInputs.apply(listOf(event))

        assertTrue(failures.isEmpty())
    }
}
