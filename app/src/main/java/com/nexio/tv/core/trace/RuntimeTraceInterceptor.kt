package com.nexio.tv.core.trace

import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.atomic.AtomicLong

class RuntimeTraceInterceptor(
    private val sink: RuntimeTraceSink,
    private val redactor: TraceRedactor,
    private val modeProvider: TraceModeProvider,
    private val unscopedGuard: UnscopedNetworkPolicyGuard
) : Interceptor {
    private val seq = AtomicLong(0L)

    override fun intercept(chain: Interceptor.Chain): Response {
        val mode = modeProvider.current
        val request = chain.request()
        val ctx = request.tag(RuntimeTraceContext::class.java)

        if (ctx == null) {
            // Untagged: report as policy violation regardless of summary mode,
            // but skip when tracing is fully OFF (no active session).
            if (mode != TraceMode.OFF) {
                unscopedGuard.reportUnscoped(request.method, request.url.toString())
            }
            return chain.proceed(request)
        }

        if (!mode.includesHttpSummary) {
            return chain.proceed(request)
        }

        val started = System.currentTimeMillis()
        emit(
            "http.request",
            mapOf(
                "runtimeOperationId" to ctx.runtimeOperationId,
                "provider" to ctx.provider.name,
                "apiShapeId" to ctx.apiShapeId,
                "method" to request.method,
                "url" to redactor.redactUrl(request.url.toString()),
                "headers" to redactor.redactHeaders(request.headers.asMap())
            ),
            ctx.traceSessionId
        )

        val response = try {
            chain.proceed(request)
        } catch (t: Throwable) {
            emit(
                "http.error",
                mapOf(
                    "runtimeOperationId" to ctx.runtimeOperationId,
                    "provider" to ctx.provider.name,
                    "apiShapeId" to ctx.apiShapeId,
                    "error" to t.message
                ),
                ctx.traceSessionId
            )
            throw t
        }

        emit(
            "http.response",
            mapOf(
                "runtimeOperationId" to ctx.runtimeOperationId,
                "provider" to ctx.provider.name,
                "apiShapeId" to ctx.apiShapeId,
                "statusCode" to response.code,
                "durationMs" to (System.currentTimeMillis() - started),
                "responseHeaders" to redactor.redactHeaders(response.headers.asMap()),
                "byteCount" to (response.body?.contentLength() ?: -1L)
            ),
            ctx.traceSessionId
        )

        return response
    }

    private fun Headers.asMap(): Map<String, String> =
        names().associateWith { values(it).joinToString(", ") }

    private fun emit(eventType: String, payload: Map<String, Any?>, sessionId: String) {
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = sessionId,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = eventType,
                payload = payload
            )
        )
    }
}
