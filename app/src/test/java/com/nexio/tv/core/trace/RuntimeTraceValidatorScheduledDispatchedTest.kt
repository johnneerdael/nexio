package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the `ScheduledResolversAreDispatched` validator rule (F-B-04, Task 22).
 *
 * Closes the second half of F-B-04 — Task 19 wired the dispatch loop in
 * `MetadataRouterFacade`; this rule catches future regressions where a code
 * change emits `metadata.resolver_schedule` but fails to follow up with the
 * corresponding `metadata.field_selected` events for each scheduled resolver
 * type.
 */
class RuntimeTraceValidatorScheduledDispatchedTest {
    private val validator = RuntimeTraceValidator()

    private fun envelope(
        eventType: String,
        sequence: Long,
        payload: Map<String, Any?>
    ): TraceEventEnvelope<Map<String, Any?>> =
        TraceEventEnvelope(
            traceSessionId = "s",
            sequence = sequence,
            wallClockMs = 1L,
            elapsedRealtimeMs = 1L,
            threadName = "t",
            eventType = eventType,
            payload = payload
        )

    @Test
    fun `validator fails when networkResolvers schedule has no matching field_selected events`() {
        val events = sequenceOf(
            envelope(
                "metadata.resolver_schedule",
                1L,
                mapOf("networkResolvers" to listOf("REVIEWS", "RECOMMENDATIONS"))
            ),
            envelope(
                "metadata.route_decision",
                2L,
                mapOf(
                    "provider" to "TMDB",
                    "usedInputs" to listOf("item.id")
                )
            )
            // No field_selected for REVIEWS or RECOMMENDATIONS — this is the bug being caught.
        )
        val report = validator.validate(events)
        assertEquals(
            "expected FAIL verdict, got ${report.verdict} (failures=${report.failures})",
            TraceVerdict.FAIL,
            report.verdict
        )
        assertTrue(
            "failures should mention ScheduledResolversAreDispatched, got ${report.failures}",
            report.failures.any { it.ruleId == "ScheduledResolversAreDispatched" }
        )
    }

    @Test
    fun `validator passes when each scheduled networkResolver has a corresponding field_selected`() {
        val events = sequenceOf(
            envelope(
                "metadata.resolver_schedule",
                1L,
                mapOf("networkResolvers" to listOf("REVIEWS"))
            ),
            envelope(
                "metadata.field_selected",
                2L,
                mapOf(
                    "field" to "REVIEWS",
                    "selectedProvider" to "TMDB",
                    "ownershipRule" to "review-resolver: aggregate"
                )
            )
        )
        val report = validator.validate(events)
        assertEquals(
            "expected PASS verdict, got ${report.verdict} (failures=${report.failures})",
            TraceVerdict.PASS,
            report.verdict
        )
    }

    @Test
    fun `validator also reads the production scheduled key with mixed local and network resolvers`() {
        // ResolverOrchestrator emits payload key "scheduled" containing both local and network
        // resolver type names (e.g. ARTWORK + TRAILERS). The rule must accept this real shape.
        val events = sequenceOf(
            envelope(
                "metadata.resolver_schedule",
                1L,
                mapOf("scheduled" to listOf("ARTWORK", "TRAILERS"))
            ),
            envelope(
                "metadata.field_selected",
                2L,
                mapOf(
                    "field" to "POSTER",
                    "selectedProvider" to "TMDB",
                    "ownershipRule" to "primary always wins"
                )
            ),
            envelope(
                "metadata.field_selected",
                3L,
                mapOf(
                    "field" to "TRAILERS",
                    "selectedProvider" to "TMDB",
                    "ownershipRule" to "trailer-resolver"
                )
            )
        )
        val report = validator.validate(events)
        assertEquals(
            "expected PASS, got ${report.verdict} (failures=${report.failures})",
            TraceVerdict.PASS,
            report.verdict
        )
    }
}
