package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class NoopRuntimeTraceSinkTest {
    @Test
    fun `noop sink is non-fatal and returns zero counters`() {
        val sink: RuntimeTraceSink = NoopRuntimeTraceSink
        sink.emit(envelope("e"))
        sink.emit(envelope("f"))
        assertEquals(0L, sink.eventsWritten())
        assertEquals(0L, sink.eventsDropped())
    }

    private fun envelope(type: String) = TraceEventEnvelope(
        traceSessionId = "s",
        sequence = 0L,
        wallClockMs = 1L,
        elapsedRealtimeMs = 1L,
        threadName = null,
        eventType = type,
        payload = Unit
    )
}
