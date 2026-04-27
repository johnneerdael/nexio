package com.nexio.tv.core.trace

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileRuntimeTraceSinkTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `emit increments writtenCount and writes JSONL`() {
        val file = tmp.newFile("trace.jsonl")
        val sink = FileRuntimeTraceSink(
            sessionId = "s1",
            writer = JsonlTraceWriter(file, Gson(), 1_000_000L),
            redactor = TraceRedactor()
        )
        sink.emit(TraceEventEnvelope(
            traceSessionId = "s1", sequence = 1L, wallClockMs = 1L, elapsedRealtimeMs = 1L,
            threadName = "t", eventType = "x", payload = mapOf("k" to "v")
        ))
        sink.close()
        assertEquals(1L, sink.eventsWritten())
        assertEquals(0L, sink.eventsDropped())
        assertFalse(file.readText().isBlank())
    }
}
