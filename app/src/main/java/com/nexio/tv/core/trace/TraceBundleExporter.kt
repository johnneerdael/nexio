package com.nexio.tv.core.trace

import com.google.gson.Gson
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class TraceBundleExporter(private val gson: Gson, private val redactor: TraceRedactor = TraceRedactor()) {

    private val summaryGenerator = TraceSummaryGenerator(gson)

    fun export(
        session: TraceSession,
        sessionDir: File,
        report: TraceValidationReport,
        events: List<TraceEventEnvelope<*>>,
        outputDir: File
    ): File {
        outputDir.mkdirs()
        val timestamp = session.startedAtEpochMs
        val zipFile = File(outputDir, "nexio-trace-$timestamp.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
            // 1. Copy trace-events.jsonl from session dir
            val eventsFile = File(sessionDir, "trace-events.jsonl")
            if (eventsFile.isFile) {
                zip.putNextEntry(ZipEntry("trace-events.jsonl"))
                eventsFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            // 2. trace-summary.json
            zip.putNextEntry(ZipEntry("trace-summary.json"))
            zip.write(summaryGenerator.toJson(report, events).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 3. trace-summary.md
            zip.putNextEntry(ZipEntry("trace-summary.md"))
            zip.write(summaryGenerator.toMarkdown(report, events).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 4. trace-validation-report.json
            zip.putNextEntry(ZipEntry("trace-validation-report.json"))
            zip.write(gson.toJson(report).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 5. redaction-manifest.json
            zip.putNextEntry(ZipEntry("redaction-manifest.json"))
            zip.write(gson.toJson(redactionManifest()).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 6. device-info.json
            zip.putNextEntry(ZipEntry("device-info.json"))
            zip.write(gson.toJson(deviceInfo(session)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            // 7. app-build-info.json
            zip.putNextEntry(ZipEntry("app-build-info.json"))
            zip.write(gson.toJson(appBuildInfo(session)).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        return zipFile
    }

    // F2-I-02: derived from TraceRedactor's live sets so the manifest never drifts.
    private fun redactionManifest(): Map<String, Any?> = mapOf(
        "schemaVersion" to 1,
        "urlQueryKeys" to redactor.urlQueryKeys().sorted(),
        "headers" to redactor.redactedHeaderNames().sorted(),
        "jsonBodyKeys" to redactor.jsonBodyKeys().sorted(),
        "profileIdentifiers" to "HMAC-SHA256(perSessionSalt, value).take(12)"
    )

    private fun deviceInfo(session: TraceSession): Map<String, Any?> = mapOf(
        "deviceModel" to session.deviceModel,
        "androidVersion" to session.androidVersion
    )

    private fun appBuildInfo(session: TraceSession): Map<String, Any?> = mapOf(
        "appVersion" to session.appVersion,
        "buildType" to session.buildType,
        "gitSha" to session.gitSha,
        "traceSessionId" to session.traceSessionId,
        "mode" to session.mode.name,
        "startedAtEpochMs" to session.startedAtEpochMs
    )
}
