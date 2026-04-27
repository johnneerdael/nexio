package com.nexio.tv.core.trace

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Protocol
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicLong

class RuntimeTraceEventListener(
    private val sink: RuntimeTraceSink,
    private val modeProvider: TraceModeProvider,
    private val contextResolver: (Call) -> RuntimeTraceContext?
) : EventListener() {
    private val seq = AtomicLong(0L)

    override fun connectStart(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy) {
        emitPhase(call, "connectStart")
    }

    override fun connectEnd(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?) {
        emitPhase(call, "connectEnd", extra = mapOf("protocol" to protocol?.toString()))
    }

    override fun secureConnectStart(call: Call) {
        emitPhase(call, "secureConnectStart")
    }

    override fun secureConnectEnd(call: Call, handshake: Handshake?) {
        emitPhase(call, "secureConnectEnd")
    }

    override fun responseHeadersStart(call: Call) {
        emitPhase(call, "responseHeadersStart")
    }

    override fun responseBodyEnd(call: Call, byteCount: Long) {
        emitPhase(call, "responseBodyEnd", extra = mapOf("byteCount" to byteCount))
    }

    private fun emitPhase(call: Call, phase: String, extra: Map<String, Any?> = emptyMap()) {
        val mode = modeProvider.current
        if (mode == TraceMode.OFF || !mode.includesHttpSummary) return
        val ctx = contextResolver(call) ?: return
        val payload = mapOf(
            "runtimeOperationId" to ctx.runtimeOperationId,
            "provider" to ctx.provider.name,
            "phase" to phase
        ) + extra
        sink.emit(
            TraceEventEnvelope(
                traceSessionId = ctx.traceSessionId,
                sequence = seq.incrementAndGet(),
                wallClockMs = System.currentTimeMillis(),
                elapsedRealtimeMs = System.nanoTime() / 1_000_000,
                threadName = Thread.currentThread().name,
                eventType = "http.timing",
                payload = payload
            )
        )
    }
}
