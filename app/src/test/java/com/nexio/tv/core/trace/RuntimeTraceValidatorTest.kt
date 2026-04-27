package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTraceValidatorTest {
    private val validator = RuntimeTraceValidator()

    private fun envelope(eventType: String, sequence: Long, payload: Map<String, Any?>) =
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
    fun `clean session passes`() {
        val report = validator.validate(emptySequence())
        assertEquals(TraceVerdict.PASS, report.verdict)
        assertTrue(report.failures.isEmpty())
        assertEquals(0L, report.totalEvents)
    }

    @Test
    fun `synthetic preview violation fails`() {
        val events = sequenceOf(envelope("metadata.first_paint", 1L, mapOf(
            "source" to "ADDON_META_PREVIEW", "routerExecuted" to true, "networkExecuted" to false
        )))
        val report = validator.validate(events)
        assertEquals(TraceVerdict.FAIL, report.verdict)
        assertTrue(report.failures.any { it.ruleId == "PreviewMustNotRouteOrNetwork" })
    }

    @Test
    fun `counters reflect event mix`() {
        val events = sequenceOf(
            envelope("runtime.cache_decision", 1L, mapOf("decision" to "HIT")),
            envelope("runtime.cache_decision", 2L, mapOf("decision" to "MISS_THEN_NETWORK")),
            envelope("runtime.cache_decision", 3L, mapOf("decision" to "STALE_HIT")),
            envelope("metadata.route_decision", 4L, mapOf("usedInputs" to listOf("item.id"))),
            envelope("http.request", 5L, mapOf("runtimeOperationId" to "op_1")),
            envelope("http.response", 6L, mapOf("runtimeOperationId" to "op_1"))
        )
        val report = validator.validate(events)
        assertEquals(6L, report.totalEvents)
        assertEquals(2L, report.httpEvents)
        assertEquals(1L, report.cacheHits)
        assertEquals(1L, report.cacheMisses)
        assertEquals(1L, report.staleHits)
        assertEquals(1L, report.routeDecisions)
    }
}
