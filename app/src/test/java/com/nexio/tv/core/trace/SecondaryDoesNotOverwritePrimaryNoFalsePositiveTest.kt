package com.nexio.tv.core.trace

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-I-06 pin: SecondaryDoesNotOverwritePrimary MUST NOT fire when the WINNER is PRIMARY,
 * even if rejectedCandidates is non-empty (i.e. there were secondary candidates that lost).
 * Only an actual secondary winner with rejected primary candidates should FAIL.
 */
class SecondaryDoesNotOverwritePrimaryNoFalsePositiveTest {

    private val rule = TraceValidationRules.SecondaryDoesNotOverwritePrimary

    private fun envelope(payload: Map<String, Any?>) =
        TraceEventEnvelope(
            traceSessionId = "s",
            sequence = 1L,
            wallClockMs = 0L,
            elapsedRealtimeMs = 0L,
            threadName = "t",
            eventType = "metadata.field_selected",
            payload = payload
        )

    @Test
    fun `primary wins with rejected secondaries produces no failures`() {
        val event = envelope(
            mapOf(
                "contentId" to "tmdb:550",
                "field" to "TITLE",
                "selectedProvider" to "TMDB",
                "sourceRole" to "PRIMARY", // PRIMARY won
                "rejectedCandidates" to listOf(
                    mapOf("provider" to "TVDB", "sourceRole" to "SECONDARY")
                )
            )
        )

        val failures = rule.apply(listOf(event))
        assertTrue(
            "Expected no failures when PRIMARY wins (false-positive guard), got: $failures",
            failures.isEmpty()
        )
    }

    @Test
    fun `secondary wins with rejected primary triggers failure`() {
        val event = envelope(
            mapOf(
                "contentId" to "tmdb:550",
                "field" to "TITLE",
                "selectedProvider" to "TVDB",
                "sourceRole" to "SECONDARY", // SECONDARY won
                "rejectedCandidates" to listOf(
                    mapOf("provider" to "TMDB", "sourceRole" to "PRIMARY")
                )
            )
        )

        val failures = rule.apply(listOf(event))
        assertTrue(
            "Expected failure when SECONDARY wins over primary on protected field, got: $failures",
            failures.isNotEmpty()
        )
    }

    @Test
    fun `no rejected candidates always produces no failures`() {
        val event = envelope(
            mapOf(
                "contentId" to "tmdb:550",
                "field" to "TITLE",
                "selectedProvider" to "TMDB",
                "sourceRole" to "PRIMARY",
                "rejectedCandidates" to emptyList<Map<String, Any?>>()
            )
        )

        val failures = rule.apply(listOf(event))
        assertTrue(
            "Expected no failures when rejectedCandidates is empty, got: $failures",
            failures.isEmpty()
        )
    }
}
