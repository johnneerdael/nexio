package com.nexio.tv.core.trace

data class TraceEventEnvelope<T>(
    val schemaVersion: Int = 1,
    val traceSessionId: String,
    val sequence: Long,
    val wallClockMs: Long,
    val elapsedRealtimeMs: Long,
    val threadName: String?,
    val eventType: String,
    val payload: T
) {
    init {
        require(traceSessionId.isNotBlank()) { "envelope.traceSessionId must not be blank" }
        require(sequence >= 0L) { "envelope.sequence must be >= 0" }
        require(eventType.isNotBlank()) { "envelope.eventType must not be blank" }
    }
}
