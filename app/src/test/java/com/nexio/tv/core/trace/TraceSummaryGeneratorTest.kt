package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceSummaryGeneratorTest {
    private val gson = Gson()
    private val generator = TraceSummaryGenerator(gson)

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

    private fun report(verdict: TraceVerdict = TraceVerdict.PASS) =
        TraceValidationReport(
            verdict = verdict,
            failures = emptyList(),
            warnings = emptyList(),
            totalEvents = 0L,
            httpEvents = 0L,
            cacheHits = 0L,
            cacheMisses = 0L,
            staleHits = 0L,
            routeDecisions = 0L
        )

    @Test
    fun `JSON includes verdict counters and per-provider counts`() {
        val events = listOf(
            envelope("http.request", 1L, mapOf("provider" to "TMDB", "durationMs" to 100L)),
            envelope("http.response", 2L, mapOf("provider" to "TMDB", "durationMs" to 100L)),
            envelope("http.request", 3L, mapOf("provider" to "TVDB"))
        )
        val json = generator.toJson(report(TraceVerdict.PASS), events)
        assertTrue("verdict in JSON", json.contains("\"verdict\":\"PASS\""))
        assertTrue("provider counts in JSON: $json", json.contains("\"TMDB\""))
        assertTrue("provider counts in JSON: $json", json.contains("\"TVDB\""))
    }

    @Test
    fun `Markdown shows verdict and section headers`() {
        val md = generator.toMarkdown(report(TraceVerdict.FAIL), emptyList())
        assertTrue("verdict in MD", md.contains("FAIL"))
        assertTrue("title in MD", md.contains("# Runtime Trace Summary"))
        assertTrue("counters section", md.contains("## Counters"))
        assertTrue("providers section", md.contains("## Providers"))
    }

    @Test
    fun `top slow operations appear when http events have durationMs`() {
        val events = listOf(
            envelope("http.response", 1L, mapOf("provider" to "TMDB", "durationMs" to 50L, "apiShapeId" to "tmdb.movie")),
            envelope("http.response", 2L, mapOf("provider" to "TMDB", "durationMs" to 1500L, "apiShapeId" to "tmdb.search")),
            envelope("http.response", 3L, mapOf("provider" to "TVDB", "durationMs" to 800L, "apiShapeId" to "tvdb.series"))
        )
        val md = generator.toMarkdown(report(TraceVerdict.PASS), events)
        assertTrue("slowest first in top slow section: $md", md.contains("tmdb.search"))
        assertTrue("includes 1500ms entry", md.contains("1500"))
    }
}
