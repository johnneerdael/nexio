package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.IOException

class JsonlTraceWriterTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `appends one line per event`() {
        val file = tmp.newFile("events.jsonl")
        val writer = JsonlTraceWriter(file = file, gson = Gson(), maxBytes = 1_000_000L)
        writer.append(envelope("a"), priority = TraceEventPriority.HIGH)
        writer.append(envelope("b"), priority = TraceEventPriority.HIGH)
        writer.close()
        val lines = file.readLines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("\"eventType\":\"a\""))
    }

    @Test
    fun `drops low priority events when over cap`() {
        val file = tmp.newFile("events.jsonl")
        // measure one envelope's serialized size, then size cap to admit ~2 HIGH events
        val sample = """{"k":"v"}"""
        val approxLine = 200L  // serialized envelope is ~120-180 bytes; 200 admits 2 lines, rejects 3rd
        val writer = JsonlTraceWriter(file = file, gson = Gson(), maxBytes = approxLine * 2)
        writer.append(envelope("hi-1"), TraceEventPriority.HIGH)
        writer.append(envelope("hi-2"), TraceEventPriority.HIGH)
        writer.append(envelope("low-1"), TraceEventPriority.LOW) // should drop — over cap
        writer.close()
        val text = file.readText()
        assertTrue(text.contains("\"eventType\":\"hi-1\""))
        assertTrue(text.contains("\"eventType\":\"hi-2\""))
        assertFalse(text.contains("\"eventType\":\"low-1\""))
        assertEquals(1L, writer.droppedCount())
    }

    @Test
    fun `appended line is on disk before close`() {
        val file = tmp.newFile("flush.jsonl")
        val writer = JsonlTraceWriter(file = file, gson = Gson(), maxBytes = 1_000_000L)
        writer.append(envelope("a"), priority = TraceEventPriority.HIGH)
        // Read without closing — the line must already be flushed to disk
        val content = file.readText()
        assertTrue("line must be flushed to disk before close: '$content'", content.contains("\"eventType\":\"a\""))
        writer.close()
    }

    @Test
    fun `BLOCKER priority is never dropped`() {
        val file = tmp.newFile("events.jsonl")
        val writer = JsonlTraceWriter(file = file, gson = Gson(), maxBytes = 10L)
        writer.append(envelope("violation"), TraceEventPriority.BLOCKER)
        writer.close()
        assertTrue(file.readText().contains("\"eventType\":\"violation\""))
    }

    // ——— F2-I-10: IOException counter ————————————————————————————————————

    @Test
    fun `IOException during write increments ioDroppedCount`() {
        // Create a BufferedWriter that always throws IOException on write
        val throwingWriter = object : BufferedWriter(java.io.StringWriter()) {
            override fun write(str: String) { throw IOException("disk full") }
            override fun flush() { throw IOException("disk full") }
            override fun close() {} // close is allowed to succeed
        }
        val writer = JsonlTraceWriter(
            writer = throwingWriter,
            gson = Gson(),
            maxBytes = 1_000_000L,
            testMarker = Unit
        )
        writer.append(envelope("ev"), priority = TraceEventPriority.HIGH)
        assertEquals(1L, writer.ioDroppedCount())
        // capacity-dropped counter must NOT be incremented — it was an IO failure, not a size drop
        assertEquals(0L, writer.droppedCount())
    }

    @Test
    fun `two IOException events each increment ioDroppedCount`() {
        val throwingWriter = object : BufferedWriter(java.io.StringWriter()) {
            override fun write(str: String) { throw IOException("enospc") }
            override fun flush() { throw IOException("enospc") }
            override fun close() {}
        }
        val writer = JsonlTraceWriter(
            writer = throwingWriter,
            gson = Gson(),
            maxBytes = 1_000_000L,
            testMarker = Unit
        )
        writer.append(envelope("ev1"), priority = TraceEventPriority.HIGH)
        writer.append(envelope("ev2"), priority = TraceEventPriority.HIGH)
        assertEquals(2L, writer.ioDroppedCount())
    }

    private fun envelope(type: String) = TraceEventEnvelope(
        traceSessionId = "s",
        sequence = 0L,
        wallClockMs = 1L,
        elapsedRealtimeMs = 1L,
        threadName = "t",
        eventType = type,
        payload = mapOf("k" to "v")
    )
}
