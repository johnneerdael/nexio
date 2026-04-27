package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRuntimeTraceSinkRingBufferTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun envelope(eventType: String, sequence: Long) = TraceEventEnvelope(
        traceSessionId = "s1",
        sequence = sequence,
        wallClockMs = 1L,
        elapsedRealtimeMs = 1L,
        threadName = "t",
        eventType = eventType,
        payload = mapOf("k" to "v")
    )

    @Test
    fun `recent returns last N events in order`() {
        val file = tmp.newFile("trace.jsonl")
        val sink = FileRuntimeTraceSink(
            sessionId = "s1",
            writer = JsonlTraceWriter(file, Gson(), 1_000_000L),
            redactor = TraceRedactor()
        )
        sink.emit(envelope("a", 1L))
        sink.emit(envelope("b", 2L))
        sink.emit(envelope("c", 3L))
        val recent = sink.recent()
        assertEquals(3, recent.size)
        assertEquals("a", recent[0].eventType)
        assertEquals("c", recent[2].eventType)
        sink.close()
    }

    @Test
    fun `recent caps at 100 entries dropping oldest`() {
        val file = tmp.newFile("trace2.jsonl")
        val sink = FileRuntimeTraceSink(
            sessionId = "s1",
            writer = JsonlTraceWriter(file, Gson(), 1_000_000L),
            redactor = TraceRedactor()
        )
        for (i in 1..150) sink.emit(envelope("e$i", i.toLong()))
        val recent = sink.recent()
        assertEquals(100, recent.size)
        assertEquals("e51", recent.first().eventType)
        assertEquals("e150", recent.last().eventType)
        sink.close()
    }
}
