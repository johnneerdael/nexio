package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTraceValidatorLocalizationPlanRuleTest {

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
    fun `validator fails when TVDB route_decision is followed by provider_plan without preceding localization_plan`() {
        val events = sequenceOf(
            envelope("metadata.route_decision", 1L, mapOf("provider" to "TVDB")),
            envelope("metadata.provider_plan", 2L, mapOf("apiShapeId" to "tvdb.series.base"))
        )
        val report = validator.validate(events)
        assertEquals(TraceVerdict.FAIL, report.verdict)
        assertTrue(
            "expected LocalizationPlanPrecedesProviderSteps failure, got ${report.failures.map { it.ruleId }}",
            report.failures.any { it.ruleId == "LocalizationPlanPrecedesProviderSteps" }
        )
    }

    @Test
    fun `validator passes when localization_plan fires between route_decision and provider_plan`() {
        val events = sequenceOf(
            envelope("metadata.route_decision", 1L, mapOf("provider" to "TVDB")),
            envelope("metadata.localization_plan", 2L, mapOf("provider" to "TVDB", "policyVersion" to 2)),
            envelope("metadata.provider_plan", 3L, mapOf("apiShapeId" to "tvdb.series.base"))
        )
        val report = validator.validate(events)
        assertEquals(TraceVerdict.PASS, report.verdict)
    }

    @Test
    fun `validator skips routes targeting providers other than TVDB TMDB Kitsu`() {
        val events = sequenceOf(
            envelope("metadata.route_decision", 1L, mapOf("provider" to "ADDON")),
            envelope("metadata.provider_plan", 2L, mapOf("apiShapeId" to "addon.meta"))
        )
        val report = validator.validate(events)
        assertEquals(TraceVerdict.PASS, report.verdict)
    }
}
