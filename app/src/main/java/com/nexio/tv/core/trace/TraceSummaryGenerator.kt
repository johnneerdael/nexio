package com.nexio.tv.core.trace

import com.google.gson.Gson

class TraceSummaryGenerator(private val gson: Gson) {

    private data class JsonSummary(
        val verdict: String,
        val totalEvents: Long,
        val httpEvents: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
        val staleHits: Long,
        val routeDecisions: Long,
        val failures: List<TraceValidationFailure>,
        val warnings: List<TraceValidationWarning>,
        val providerCounts: Map<String, Long>,
        val topSlowOps: List<SlowOpEntry>,
        val cacheProofs: List<TraceCacheProofEntry>
    )

    private data class SlowOpEntry(val provider: String?, val apiShapeId: String?, val durationMs: Long)

    fun toJson(report: TraceValidationReport, events: List<TraceEventEnvelope<*>>): String {
        val summary = JsonSummary(
            verdict = report.verdict.name,
            totalEvents = report.totalEvents,
            httpEvents = report.httpEvents,
            cacheHits = report.cacheHits,
            cacheMisses = report.cacheMisses,
            staleHits = report.staleHits,
            routeDecisions = report.routeDecisions,
            failures = report.failures,
            warnings = report.warnings,
            providerCounts = providerCounts(events),
            topSlowOps = topSlowOps(events),
            cacheProofs = report.cacheProofs
        )
        return gson.toJson(summary)
    }

    fun toMarkdown(report: TraceValidationReport, events: List<TraceEventEnvelope<*>>): String = buildString {
        appendLine("# Runtime Trace Summary")
        appendLine()
        appendLine("- Verdict: **${report.verdict.name}**")
        appendLine("- Total events: ${report.totalEvents}")
        appendLine("- HTTP events: ${report.httpEvents}")
        appendLine()
        appendLine("## Counters")
        appendLine("- Cache hits: ${report.cacheHits}")
        appendLine("- Cache misses: ${report.cacheMisses}")
        appendLine("- Stale hits: ${report.staleHits}")
        appendLine("- Route decisions: ${report.routeDecisions}")
        appendLine()
        appendLine("## Providers")
        providerCounts(events).forEach { (p, n) -> appendLine("- $p: $n calls") }
        appendLine()
        appendLine("## Cache Proof")
        if (report.cacheProofs.isEmpty()) {
            appendLine("- None")
        } else {
            report.cacheProofs.forEach {
                appendLine(
                    "- ${it.provider ?: "?"} / ${it.apiShapeId ?: "?"} / ${it.operationKey ?: "?"}: " +
                        "decision=${it.cacheDecision ?: "?"}, " +
                        "networkSuppressed=${it.networkSuppressed}, " +
                        "httpRequests=${it.httpRequestCount}, " +
                        "cacheKey=${it.cacheKey ?: "?"}"
                )
            }
        }
        appendLine()
        if (report.failures.isNotEmpty()) {
            appendLine("## Failures")
            report.failures.forEach { appendLine("- `${it.ruleId}` (seq ${it.sequence}): ${it.message}") }
            appendLine()
        }
        appendLine("## Top slow operations")
        topSlowOps(events).take(5).forEach {
            appendLine("- ${it.provider ?: "?"} / ${it.apiShapeId ?: "?"}: ${it.durationMs}ms")
        }
    }

    private fun providerCounts(events: List<TraceEventEnvelope<*>>): Map<String, Long> {
        val counts = linkedMapOf<String, Long>()
        events.filter { it.eventType == "http.request" }.forEach { e ->
            val provider = (e.payload as? Map<*, *>)?.get("provider") as? String ?: return@forEach
            counts[provider] = (counts[provider] ?: 0L) + 1L
        }
        return counts.toList().sortedByDescending { it.second }.toMap()
    }

    private fun topSlowOps(events: List<TraceEventEnvelope<*>>): List<SlowOpEntry> =
        events.filter { it.eventType == "http.response" }
            .mapNotNull { e ->
                val p = e.payload as? Map<*, *> ?: return@mapNotNull null
                val duration = (p["durationMs"] as? Number)?.toLong() ?: return@mapNotNull null
                SlowOpEntry(
                    provider = p["provider"] as? String,
                    apiShapeId = p["apiShapeId"] as? String,
                    durationMs = duration
                )
            }
            .sortedByDescending { it.durationMs }
            .take(10)
}
