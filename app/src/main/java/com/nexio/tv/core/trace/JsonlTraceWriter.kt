package com.nexio.tv.core.trace

import com.google.gson.Gson
import java.io.File
import java.io.PrintWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicLong

enum class TraceEventPriority { BLOCKER, HIGH, MEDIUM, LOW, VERBOSE }

class JsonlTraceWriter(
    file: File,
    private val gson: Gson,
    private val maxBytes: Long
) {
    private val out: Writer = PrintWriter(file.outputStream().buffered())
    private val written = AtomicLong(0L)
    private val dropped = AtomicLong(0L)

    @Synchronized
    fun append(event: TraceEventEnvelope<*>, priority: TraceEventPriority) {
        val line = gson.toJson(event) + "\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (priority != TraceEventPriority.BLOCKER && written.get() + lineBytes > maxBytes) {
            dropped.incrementAndGet()
            return
        }
        out.write(line)
        written.addAndGet(lineBytes)
    }

    fun droppedCount(): Long = dropped.get()
    fun writtenBytes(): Long = written.get()

    @Synchronized
    fun close() {
        out.flush()
        out.close()
    }
}
