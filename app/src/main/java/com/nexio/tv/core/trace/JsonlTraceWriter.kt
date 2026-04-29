package com.nexio.tv.core.trace

import com.google.gson.Gson
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicLong

enum class TraceEventPriority { BLOCKER, HIGH, MEDIUM, LOW, VERBOSE }

class JsonlTraceWriter private constructor(
    private val out: BufferedWriter,
    private val gson: Gson,
    private val maxBytes: Long
) {
    constructor(
        file: File,
        gson: Gson,
        maxBytes: Long
    ) : this(
        out = BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8)),
        gson = gson,
        maxBytes = maxBytes
    )

    /** Internal constructor for testing — allows injecting a writer that throws [IOException]. */
    internal constructor(
        writer: BufferedWriter,
        gson: Gson,
        maxBytes: Long,
        @Suppress("UNUSED_PARAMETER") testMarker: Unit
    ) : this(out = writer, gson = gson, maxBytes = maxBytes)

    private val written = AtomicLong(0L)
    private val dropped = AtomicLong(0L)
    // F2-I-10: counts events lost due to IOException (e.g. storage-full) during write/flush
    private val ioDropped = AtomicLong(0L)

    @Synchronized
    fun append(event: TraceEventEnvelope<*>, priority: TraceEventPriority) {
        val line = gson.toJson(event) + "\n"
        val lineBytes = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (priority != TraceEventPriority.BLOCKER && written.get() + lineBytes > maxBytes) {
            dropped.incrementAndGet()
            return
        }
        try {
            out.write(line)
            out.flush()
            written.addAndGet(lineBytes)
        } catch (e: IOException) {
            ioDropped.incrementAndGet()
        }
    }

    fun droppedCount(): Long = dropped.get()
    /** Returns the number of events dropped due to [IOException] (e.g. storage-full). */
    fun ioDroppedCount(): Long = ioDropped.get()
    fun writtenBytes(): Long = written.get()

    @Synchronized
    fun close() {
        try {
            out.flush()
            out.close()
        } catch (_: IOException) {
            // best-effort close; ioDropped is already incremented by append() for lost writes
        }
    }
}
