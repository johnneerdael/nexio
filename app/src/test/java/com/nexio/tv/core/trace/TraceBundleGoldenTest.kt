package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class TraceBundleGoldenTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `synthetic session validates PASS and bundle has no raw tokens`() {
        val tracesRoot = tmp.newFolder("traces")
        val gson = Gson()
        val manager = TraceSessionManager(
            tracesRoot = tracesRoot,
            gson = gson,
            clock = { 1_700_000_000_000L },
            buildInfo = TraceBuildInfo(
                appVersion = "1.0",
                buildType = "debug",
                gitSha = "deadbeef",
                deviceModel = "Pixel",
                androidVersion = "14"
            )
        )
        manager.start(TraceMode.INCLUDE_HTTP_SUMMARY, activeProfileHash = "ph_abc")
        val session = checkNotNull(manager.activeSession())
        val sink = manager.activeSink() as FileRuntimeTraceSink

        // Drive a small synthetic event sequence: runtime op + http exchange + cache hit + route decision.
        sink.emit(envelope(session.traceSessionId, "runtime.operation_start", 1L, mapOf(
            "runtimeOperationId" to "op_1",
            "provider" to "TMDB",
            "apiShapeId" to "tmdb.movie.core",
            "scope" to "GlobalLocalizedContent"
        )))
        sink.emit(envelope(session.traceSessionId, "http.request", 2L, mapOf(
            "runtimeOperationId" to "op_1",
            "provider" to "TMDB",
            "url" to "https://api.themoviedb.org/3/movie/550?api_key=<redacted>",
            "headers" to mapOf("Authorization" to "<redacted>")
        )))
        sink.emit(envelope(session.traceSessionId, "http.response", 3L, mapOf(
            "runtimeOperationId" to "op_1",
            "provider" to "TMDB",
            "statusCode" to 200,
            "durationMs" to 184L
        )))
        sink.emit(envelope(session.traceSessionId, "runtime.cache_decision", 4L, mapOf(
            "runtimeOperationId" to "op_1",
            "decision" to "MISS_THEN_NETWORK",
            "cacheKey" to "metadata:TMDB:tmdb.movie.core:550:en"
        )))
        sink.emit(envelope(session.traceSessionId, "metadata.route_decision", 5L, mapOf(
            "contentId" to "tt0111161",
            "provider" to "TMDB",
            "usedInputs" to listOf("item.id", "item.type"),
            "ignoredInputs" to listOf("catalog.type", "addon.name")
        )))

        manager.stop()

        // Read events back from disk
        val sessionDir = File(tracesRoot, session.traceSessionId)
        val eventsFile = File(sessionDir, "trace-events.jsonl")
        assertTrue("trace-events.jsonl exists", eventsFile.isFile)

        // Parse JSONL into envelopes for the validator
        val parsedEvents = eventsFile.readLines()
            .filter { it.isNotBlank() }
            .map { gson.fromJson(it, TraceEventEnvelope::class.java) as TraceEventEnvelope<*> }

        // Validate
        val report = RuntimeTraceValidator().validate(parsedEvents.asSequence())
        assertEquals("clean synthetic session must PASS, failures=${report.failures}", TraceVerdict.PASS, report.verdict)

        // Export bundle
        val outputDir = tmp.newFolder("export")
        val zip = TraceBundleExporter(gson).export(
            session = session,
            sessionDir = sessionDir,
            report = report,
            events = parsedEvents,
            outputDir = outputDir
        )

        // Assert required entries present
        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            listOf(
                "trace-events.jsonl", "trace-summary.json", "trace-summary.md",
                "trace-validation-report.json", "redaction-manifest.json",
                "device-info.json", "app-build-info.json"
            ).forEach { entry ->
                assertTrue("bundle missing $entry, present: $names", entry in names)
            }
        }

        // Assert no raw tokens leaked
        val rawBytes = zip.readBytes()
        val text = rawBytes.toString(Charsets.UTF_8)
        assertFalse("must not contain raw 'Bearer SECRET'", text.contains("Bearer SECRET"))
        assertFalse("must not contain raw api_key=ABC123", text.contains("api_key=ABC123"))
    }

    private fun envelope(sessionId: String, eventType: String, seq: Long, payload: Map<String, Any?>) =
        TraceEventEnvelope(
            traceSessionId = sessionId,
            sequence = seq,
            wallClockMs = 1L,
            elapsedRealtimeMs = 1L,
            threadName = "test",
            eventType = eventType,
            payload = payload
        )
}
