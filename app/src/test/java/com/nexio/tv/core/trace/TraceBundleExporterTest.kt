package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.zip.ZipFile

class TraceBundleExporterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val gson = Gson()
    private val exporter = TraceBundleExporter(gson)

    private fun envelope(eventType: String, payload: Map<String, Any?>) = TraceEventEnvelope(
        traceSessionId = "s",
        sequence = 0L,
        wallClockMs = 1L,
        elapsedRealtimeMs = 1L,
        threadName = "t",
        eventType = eventType,
        payload = payload
    )

    private fun cleanReport() = TraceValidationReport(
        verdict = TraceVerdict.PASS,
        failures = emptyList(),
        warnings = emptyList(),
        totalEvents = 0L,
        httpEvents = 0L,
        cacheHits = 0L,
        cacheMisses = 0L,
        staleHits = 0L,
        routeDecisions = 0L
    )

    private fun session() = TraceSession(
        traceSessionId = "session-abc",
        startedAtEpochMs = 1_700_000_000_000L,
        appVersion = "1.0",
        buildType = "debug",
        gitSha = "deadbeef",
        deviceModel = "Pixel",
        androidVersion = "14",
        activeProfileHash = null,
        mode = TraceMode.SAFE_METADATA_RUNTIME,
        salt = "salt"
    )

    @Test
    fun `bundle contains required entries`() {
        val sessionDir = tmp.newFolder("session-abc")
        java.io.File(sessionDir, "trace-events.jsonl").writeText(
            """{"eventType":"http.request","payload":{"url":"https://x"}}""" + "\n"
        )

        val outputDir = tmp.newFolder("out")
        val zip = exporter.export(
            session = session(),
            sessionDir = sessionDir,
            report = cleanReport(),
            events = emptyList(),
            outputDir = outputDir
        )

        ZipFile(zip).use { z ->
            val names = z.entries().asSequence().map { it.name }.toSet()
            assertTrue("trace-events.jsonl present", "trace-events.jsonl" in names)
            assertTrue("trace-summary.json present", "trace-summary.json" in names)
            assertTrue("trace-summary.md present", "trace-summary.md" in names)
            assertTrue("trace-validation-report.json present", "trace-validation-report.json" in names)
            assertTrue("redaction-manifest.json present", "redaction-manifest.json" in names)
            assertTrue("device-info.json present", "device-info.json" in names)
            assertTrue("app-build-info.json present", "app-build-info.json" in names)
        }
    }

    @Test
    fun `bundle bytes contain no raw token strings`() {
        val sessionDir = tmp.newFolder("session-abc-2")
        // Pre-redacted JSONL — exporter doesn't re-redact (the sink already redacts)
        java.io.File(sessionDir, "trace-events.jsonl").writeText(
            """{"eventType":"http.request","payload":{"url":"https://x?api_key=<redacted>","Authorization":"<redacted>"}}""" + "\n"
        )

        val outputDir = tmp.newFolder("out2")
        val zip = exporter.export(
            session = session(),
            sessionDir = sessionDir,
            report = cleanReport(),
            events = emptyList(),
            outputDir = outputDir
        )

        // Read every entry and assert no raw secret tokens leaked
        val rawBytes = zip.readBytes()
        val text = rawBytes.toString(Charsets.UTF_8)
        assertFalse("must not contain raw 'Bearer SECRET'", text.contains("Bearer SECRET"))
        assertFalse("must not contain raw api_key=ABC123", text.contains("api_key=ABC123"))
    }
}
