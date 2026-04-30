package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeRuntimeTraceSinkTest {

    private fun envelope(seq: Long): TraceEventEnvelope<*> = TraceEventEnvelope(
        traceSessionId = "s",
        sequence = seq,
        wallClockMs = 0L,
        elapsedRealtimeMs = 0L,
        threadName = null,
        eventType = "metadata.route_decision",
        payload = mapOf<String, Any?>("contentId" to "x")
    )

    private class CountingSink : RuntimeTraceSink {
        var seen = 0
        override fun emit(event: TraceEventEnvelope<*>) { seen++ }
        override fun eventsWritten(): Long = seen.toLong()
        override fun eventsDropped(): Long = 0L
    }

    private class ThrowingSink : RuntimeTraceSink {
        var attempted = 0
        override fun emit(event: TraceEventEnvelope<*>) {
            attempted++
            throw RuntimeException("boom")
        }
        override fun eventsWritten(): Long = 0L
        override fun eventsDropped(): Long = attempted.toLong()
    }

    @Test
    fun `forwards every event to every sink`() {
        val a = CountingSink()
        val b = CountingSink()
        val composite = CompositeRuntimeTraceSink(listOf(a, b))
        composite.emit(envelope(1))
        composite.emit(envelope(2))
        assertEquals(2, a.seen)
        assertEquals(2, b.seen)
    }

    @Test
    fun `failure in one sink does not block other sinks`() {
        val ok = CountingSink()
        val bad = ThrowingSink()
        val composite = CompositeRuntimeTraceSink(listOf(bad, ok))
        composite.emit(envelope(1))
        composite.emit(envelope(2))
        assertEquals(2, bad.attempted)
        assertEquals(2, ok.seen)
    }

    @Test
    fun `eventsWritten returns max across sinks`() {
        val a = CountingSink().apply { repeat(3) { emit(envelope(it.toLong())) } }
        val b = CountingSink().apply { repeat(5) { emit(envelope(it.toLong())) } }
        val composite = CompositeRuntimeTraceSink(listOf(a, b))
        assertEquals(5L, composite.eventsWritten())
    }

    @Test
    fun `eventsDropped returns sum across sinks`() {
        val bad1 = ThrowingSink().apply { runCatching { emit(envelope(1)) } }
        // bad2 attempts two emits independently so attempted reaches 2
        val bad2 = ThrowingSink().apply {
            runCatching { emit(envelope(2)) }
            runCatching { emit(envelope(3)) }
        }
        val composite = CompositeRuntimeTraceSink(listOf(bad1, bad2))
        assertEquals(3L, composite.eventsDropped())
    }
}
