package com.nexio.tv.core.trace

import android.util.Log

interface LogcatChannelGate {
    fun isEnabled(channel: LogcatTraceChannel): Boolean
}

class LogcatRuntimeTraceSink(
    private val gate: LogcatChannelGate
) : RuntimeTraceSink {
    @Volatile private var written: Long = 0L

    override fun emit(event: TraceEventEnvelope<*>) {
        val channel = LogcatTraceChannel.forEventType(event.eventType) ?: return
        if (!gate.isEnabled(channel)) return
        Log.i(channel.tag, format(event))
        written++
    }

    override fun eventsWritten(): Long = written
    override fun eventsDropped(): Long = 0L

    private fun format(event: TraceEventEnvelope<*>): String {
        val payload = (event.payload as? Map<*, *>) ?: return baseLine(event)
        val curated = curatedFields(event.eventType, payload)
        val formatted = curated.entries.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
        return if (formatted.isEmpty()) baseLine(event) else "${baseLine(event)} $formatted"
    }

    private fun baseLine(event: TraceEventEnvelope<*>): String =
        "seq=${event.sequence} t=${event.eventType}"

    private fun curatedFields(eventType: String, payload: Map<*, *>): Map<String, Any?> = when (eventType) {
        "metadata.first_paint" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "surface" to payload["surface"],
            "source" to payload["source"],
            "routerExecuted" to payload["routerExecuted"],
            "networkExecuted" to payload["networkExecuted"],
            "used" to payload["fieldsUsed"],
            "profile" to payload["profileHash"]
        )
        "metadata.route_decision" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "mediaKind" to payload["mediaKind"],
            "reason" to payload["reason"]
        )
        "metadata.identity_resolution" -> linkedMapOf(
            "sourceId" to payload["sourceId"],
            "targetProvider" to payload["targetProvider"],
            "resolver" to payload["resolver"],
            "cacheDecision" to payload["cacheDecision"],
            "executedNetwork" to payload["executedNetwork"],
            "success" to payload["success"],
            "resultId" to payload["resultId"]
        )
        "metadata.provider_plan" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "mediaKind" to payload["mediaKind"],
            "depth" to payload["depth"],
            "stepCount" to (payload["steps"] as? List<*>)?.size
        )
        "metadata.resolver_schedule" -> linkedMapOf(
            "depth" to payload["depth"],
            "scheduled" to payload["scheduled"],
            "skippedCount" to (payload["skipped"] as? Map<*, *>)?.size
        )
        "metadata.field_selected" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "field" to payload["field"],
            "selectedProvider" to payload["selectedProvider"],
            "sourceRole" to payload["sourceRole"],
            "ownershipRule" to payload["ownershipRule"],
            "rejectedCount" to (payload["rejectedCandidates"] as? List<*>)?.size
        )
        "metadata.localization_plan" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "provider" to payload["provider"],
            "requestedLanguage" to payload["requestedLanguage"],
            "fallbackLanguage" to payload["fallbackLanguage"],
            "requestedIsFallback" to payload["requestedIsFallback"],
            "localeCollapsedToFallback" to payload["localeCollapsedToFallback"]
        )
        "metadata.normalizer_warning" -> linkedMapOf(
            "contentId" to payload["contentId"],
            "reason" to payload["reason"]
        )
        "metadata.stable_id_bundle" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "itemType" to payload["itemType"],
            "status" to payload["status"],
            "trigger" to payload["trigger"],
            "tmdbMovieId" to payload["tmdbMovieId"],
            "tvdbSeriesId" to payload["tvdbSeriesId"],
            "kitsuAnimeId" to payload["kitsuAnimeId"],
            "imdbId" to payload["imdbId"],
            "networkExecuted" to payload["networkExecuted"]
        )
        "home.hydration_started" -> linkedMapOf(
            "railId" to payload["railId"],
            "itemKey" to payload["itemKey"],
            "firstPaintSource" to payload["firstPaintSource"],
            "trigger" to payload["trigger"],
            "priority" to payload["priority"],
            "workClass" to payload["workClass"]
        )
        "home.hydration_overlay_written" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "canonicalProvider" to payload["canonicalProvider"],
            "canonicalId" to payload["canonicalId"],
            "imdbId" to payload["imdbId"],
            "displayHash" to payload["displayHash"]
        )
        "home.hydration_applied" -> linkedMapOf(
            "railId" to payload["railId"],
            "itemKey" to payload["itemKey"],
            "firstPaintSource" to payload["firstPaintSource"],
            "canonicalProvider" to payload["canonicalProvider"],
            "canonicalId" to payload["canonicalId"],
            "imdbId" to payload["imdbId"],
            "trigger" to payload["trigger"],
            "priority" to payload["priority"],
            "workClass" to payload["workClass"],
            "changedFields" to payload["changedFields"],
            "rowOrderChanged" to payload["rowOrderChanged"],
            "focusChanged" to payload["focusChanged"],
            "networkExecuted" to payload["networkExecuted"],
            "cacheDecision" to payload["cacheDecision"]
        )
        "home.hydration_ignored" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "reason" to payload["reason"],
            "trigger" to payload["trigger"]
        )
        "home.hydration_failed_using_preview" -> linkedMapOf(
            "itemKey" to payload["itemKey"],
            "reason" to payload["reason"],
            "trigger" to payload["trigger"]
        )
        "runtime.operation_start" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "scope" to payload["scope"]
        )
        "runtime.operation_finish" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "outcome" to payload["outcome"],
            "durationMs" to payload["durationMs"]
        )
        "runtime.operation_failed" -> linkedMapOf(
            "provider" to payload["provider"],
            "operationKey" to payload["operationKey"],
            "error" to payload["error"],
            "durationMs" to payload["durationMs"]
        )
        "runtime.cache_decision" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "operationKey" to payload["operationKey"],
            "decision" to payload["decision"],
            "reason" to payload["reason"],
            "networkSuppressed" to payload["networkSuppressed"],
            "ttlMs" to payload["ttlMs"],
            "staleWindowMs" to payload["staleWindowMs"],
            "cacheKey" to payload["cacheKey"]
        )
        "http.request" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "method" to payload["method"],
            "url" to payload["url"]
        )
        "http.response" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "statusCode" to payload["statusCode"],
            "durationMs" to payload["durationMs"],
            "byteCount" to payload["byteCount"]
        )
        "http.error" -> linkedMapOf(
            "runtimeOperationId" to payload["runtimeOperationId"],
            "provider" to payload["provider"],
            "apiShapeId" to payload["apiShapeId"],
            "error" to payload["error"]
        )
        "trace.body_sample" -> linkedMapOf(
            "provider" to payload["provider"],
            "byteCount" to payload["byteCount"]
        )
        else -> emptyMap()
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is List<*> -> v.joinToString(separator = ",", prefix = "[", postfix = "]") { it?.toString() ?: "null" }
        is Map<*, *> -> v.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { "${it.key}=${it.value}" }
        else -> v.toString()
    }
}
