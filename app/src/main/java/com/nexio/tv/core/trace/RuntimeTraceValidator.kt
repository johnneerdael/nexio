package com.nexio.tv.core.trace

class RuntimeTraceValidator(
    private val rules: List<TraceValidationRule> = TraceValidationRules.ALL
) {
    private fun payloadMap(event: TraceEventEnvelope<*>): Map<*, *> =
        event.payload as? Map<*, *> ?: emptyMap<Any, Any>()

    fun validate(events: Sequence<TraceEventEnvelope<*>>): TraceValidationReport {
        val list = events.toList()
        val failures = rules.flatMap { it.apply(list) }
        val verdict = when {
            failures.isNotEmpty() -> TraceVerdict.FAIL
            else -> TraceVerdict.PASS
        }
        return TraceValidationReport(
            verdict = verdict,
            failures = failures,
            warnings = emptyList(),
            totalEvents = list.size.toLong(),
            httpEvents = list.count { it.eventType.startsWith("http.") }.toLong(),
            cacheHits = list.count {
                it.eventType == "runtime.cache_decision" &&
                    (it.payload as? Map<*, *>)?.get("decision") == "HIT"
            }.toLong(),
            cacheMisses = list.count {
                it.eventType == "runtime.cache_decision" &&
                    (it.payload as? Map<*, *>)?.get("decision") == "MISS_THEN_NETWORK"
            }.toLong(),
            staleHits = list.count {
                it.eventType == "runtime.cache_decision" &&
                    (it.payload as? Map<*, *>)?.get("decision") == "STALE_HIT"
            }.toLong(),
            routeDecisions = list.count { it.eventType == "metadata.route_decision" }.toLong(),
            cacheProofs = cacheProofs(list)
        )
    }

    private fun cacheProofs(events: List<TraceEventEnvelope<*>>): List<TraceCacheProofEntry> {
        val httpRequestsByOperation = events
            .filter { it.eventType == "http.request" }
            .mapNotNull { payloadMap(it)["runtimeOperationId"] as? String }
            .groupingBy { it }
            .eachCount()

        return events
            .filter { it.eventType == "runtime.cache_decision" }
            .mapNotNull { event ->
                val payload = payloadMap(event)
                val runtimeOperationId = payload["runtimeOperationId"] as? String ?: return@mapNotNull null
                TraceCacheProofEntry(
                    runtimeOperationId = runtimeOperationId,
                    provider = payload["provider"] as? String,
                    apiShapeId = payload["apiShapeId"] as? String,
                    operationKey = payload["operationKey"] as? String,
                    cacheKey = payload["cacheKey"] as? String,
                    cacheDecision = payload["decision"] as? String,
                    networkSuppressed = payload["networkSuppressed"] as? Boolean,
                    httpRequestCount = httpRequestsByOperation[runtimeOperationId]?.toLong() ?: 0L
                )
            }
    }
}
