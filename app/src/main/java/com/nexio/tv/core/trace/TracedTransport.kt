package com.nexio.tv.core.trace

import java.util.concurrent.atomic.AtomicLong

/**
 * Result wrapper produced by a [TracedTransport.execute] lambda. Carries the HTTP
 * status code + response size so [TracedTransport] can emit the matching `http.response`
 * envelope, plus the typed payload returned to the caller.
 */
data class TracedResponse<T>(
    val statusCode: Int,
    val byteCount: Long,
    val payload: T
)

/**
 * Helper for HTTP transports that bypass OkHttp (raw `HttpURLConnection`,
 * native streaming, etc.). Wrap the network call in [execute] so it produces the
 * same `http.request`/`http.response` events the OkHttp interceptor produces.
 */
class TracedTransport(
    private val sink: RuntimeTraceSink,
    private val redactor: TraceRedactor
) {
    private val seq = AtomicLong(0L)

    fun <T> execute(
        ctx: RuntimeTraceContext,
        method: String,
        url: String,
        block: () -> TracedResponse<T>
    ): T {
        val started = System.currentTimeMillis()
        emit("http.request", mapOf(
            "runtimeOperationId" to ctx.runtimeOperationId,
            "provider" to ctx.provider.name,
            "apiShapeId" to ctx.apiShapeId,
            "method" to method,
            "url" to redactor.redactUrl(url)
        ), ctx.traceSessionId)

        val response = try {
            block()
        } catch (t: Throwable) {
            emit("http.error", mapOf(
                "runtimeOperationId" to ctx.runtimeOperationId,
                "provider" to ctx.provider.name,
                "apiShapeId" to ctx.apiShapeId,
                "error" to t.message
            ), ctx.traceSessionId)
            throw t
        }

        emit("http.response", mapOf(
            "runtimeOperationId" to ctx.runtimeOperationId,
            "provider" to ctx.provider.name,
            "apiShapeId" to ctx.apiShapeId,
            "statusCode" to response.statusCode,
            "durationMs" to (System.currentTimeMillis() - started),
            "byteCount" to response.byteCount
        ), ctx.traceSessionId)

        return response.payload
    }

    private fun emit(eventType: String, payload: Map<String, Any?>, sessionId: String) {
        sink.emit(TraceEventEnvelope(
            traceSessionId = sessionId,
            sequence = seq.incrementAndGet(),
            wallClockMs = System.currentTimeMillis(),
            elapsedRealtimeMs = System.nanoTime() / 1_000_000,
            threadName = Thread.currentThread().name,
            eventType = eventType,
            payload = payload
        ))
    }
}
