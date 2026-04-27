package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TraceValidationReportTest {
    @Test
    fun `failure carries ruleId and message`() {
        val f = TraceValidationFailure(ruleId = "PreviewMustNotRouteOrNetwork", message = "preview routed", sequence = 7L)
        assertEquals("PreviewMustNotRouteOrNetwork", f.ruleId)
        assertEquals(7L, f.sequence)
    }

    @Test
    fun `report exposes counters and verdict`() {
        val report = TraceValidationReport(
            verdict = TraceVerdict.PASS,
            failures = emptyList(),
            warnings = emptyList(),
            totalEvents = 100L,
            httpEvents = 30L,
            cacheHits = 10L,
            cacheMisses = 5L,
            staleHits = 2L,
            routeDecisions = 8L
        )
        assertEquals(TraceVerdict.PASS, report.verdict)
        assertEquals(100L, report.totalEvents)
        assertEquals(8L, report.routeDecisions)
    }

    @Test
    fun `failure rejects blank ruleId`() {
        assertThrows(IllegalArgumentException::class.java) {
            TraceValidationFailure(ruleId = "", message = "x", sequence = 0L)
        }
    }
}
