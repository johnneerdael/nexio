package com.nexio.tv.core.trace

import java.util.concurrent.atomic.AtomicLong

class UnscopedNetworkPolicyGuard(
    private val sink: RuntimeTraceSink,
    private val sessionId: () -> String?,
    private val isInternalBuild: Boolean
) {
    private val seq = AtomicLong(0L)

    fun reportUnscoped(method: String, url: String) {
        val payload = mapOf(
            "method" to method,
            "url" to url,
            "isInternalBuild" to isInternalBuild
        )
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sessionId() ?: "noop",
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "policy.unscoped_network_call",
                payload = payload
            )
        )
        if (isInternalBuild) {
            throw IllegalStateException("UNSCOPED_NETWORK_CALL: $method $url has no RuntimeTraceContext")
        }
    }
}
