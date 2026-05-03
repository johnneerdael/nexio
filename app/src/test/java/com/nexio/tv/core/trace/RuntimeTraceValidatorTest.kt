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

    @Test
    fun `cache proofs include KITSU hit with zero http requests`() {
        val report = validator.validate(sequenceOf(
            envelope("runtime.cache_decision", 1L, mapOf(
                "runtimeOperationId" to "op_kitsu_1",
                "provider" to "KITSU",
                "apiShapeId" to "kitsu.anime.detail",
                "operationKey" to "anime:1",
                "cacheKey" to "metadata:KITSU:kitsu.anime.detail:1",
                "decision" to "HIT",
                "networkSuppressed" to true
            ))
        ))

        assertEquals(1, report.cacheProofs.size)
        val proof = report.cacheProofs.single()
        assertEquals("op_kitsu_1", proof.runtimeOperationId)
        assertEquals("KITSU", proof.provider)
        assertEquals("kitsu.anime.detail", proof.apiShapeId)
        assertEquals("anime:1", proof.operationKey)
        assertEquals("metadata:KITSU:kitsu.anime.detail:1", proof.cacheKey)
        assertEquals("HIT", proof.cacheDecision)
        assertEquals(true, proof.networkSuppressed)
        assertEquals(0L, proof.httpRequestCount)
    }
}
