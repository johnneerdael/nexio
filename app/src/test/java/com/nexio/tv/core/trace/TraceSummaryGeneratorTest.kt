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

    private fun reportWithCacheProof() =
        report().copy(
            cacheProofs = listOf(
                TraceCacheProofEntry(
                    runtimeOperationId = "op_kitsu_1",
                    provider = "KITSU",
                    apiShapeId = "kitsu.anime.detail",
                    operationKey = "anime:1",
                    cacheKey = "metadata:KITSU:kitsu.anime.detail:1",
                    cacheDecision = "HIT",
                    networkSuppressed = true,
                    httpRequestCount = 0L
                )
            )
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
    fun `JSON includes cache proofs`() {
        val json = generator.toJson(reportWithCacheProof(), emptyList())
        assertTrue("cache proofs in JSON: $json", json.contains("\"cacheProofs\""))
        assertTrue("runtime operation id in JSON: $json", json.contains("\"runtimeOperationId\":\"op_kitsu_1\""))
        assertTrue("http request count in JSON: $json", json.contains("\"httpRequestCount\":0"))
    }

    @Test
    fun `Markdown includes cache proof section`() {
        val md = generator.toMarkdown(reportWithCacheProof(), emptyList())
        assertTrue("cache proof section in MD: $md", md.contains("## Cache Proof"))
        assertTrue("provider in MD: $md", md.contains("KITSU"))
        assertTrue("api shape in MD: $md", md.contains("kitsu.anime.detail"))
        assertTrue("operation key in MD: $md", md.contains("anime:1"))
        assertTrue("decision in MD: $md", md.contains("HIT"))
        assertTrue("network suppressed in MD: $md", md.contains("networkSuppressed=true"))
        assertTrue("http requests in MD: $md", md.contains("httpRequests=0"))
        assertTrue("cache key in MD: $md", md.contains("metadata:KITSU:kitsu.anime.detail:1"))
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
