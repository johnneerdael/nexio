package com.nexio.tv.instrumentation

import android.os.Trace
import org.jctools.queues.MpscArrayQueue
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.Writer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Owns the per-session MPSC ring + dedicated writer thread that drains
 * [TraceRecord]s into a rotating JSONL file under
 * `filesDir/playback-traces/<sessionId>.jsonl`.
 *
 *  - capacity: power-of-two, default 8192
 *  - rotation: ≤ 8 MiB per file (suffix `-N.jsonl`)
 *  - drop-on-overflow: counter emitted as `tracer_overflow` every 10 s and
 *    once more from `playback_session_ended` summary
 *
 * The constructor optionally accepts a pre-built [Writer] sink for tests so
 * the file system is not required.
 */
@PublishedApi
internal class SessionWriter(
    private val header: SessionHeader,
    private val baseFile: File?,
    capacity: Int = DEFAULT_CAPACITY,
    private val testSink: Writer? = null,
    private val rotationBytes: Long = DEFAULT_ROTATION_BYTES,
    private val parkNanos: Long = 200_000L,
    private val overflowReportIntervalNanos: Long = 10_000_000_000L
) {
    private val ring: MpscArrayQueue<TraceRecord> = MpscArrayQueue(capacity)
    private val overflowCount = AtomicLong(0)
    private val totalEmitted = AtomicLong(0)
    private val running = AtomicBoolean(true)
    private val drained = AtomicBoolean(false)
    private val lineBuffer = StringBuilder(256)

    private var rotationIndex: Int = 0
    private var bytesWrittenInCurrentFile: Long = 0L
    private var sink: Writer = testSink ?: openFile(currentFile())

    val sessionId: String get() = header.sessionId

    private val writerThread: Thread = Thread({ drainLoop() }, "PlaybackTracer-${header.sessionId}").apply {
        priority = (Thread.NORM_PRIORITY - 1).coerceAtLeast(Thread.MIN_PRIORITY)
        isDaemon = true
        start()
    }

    @PublishedApi
    internal fun enqueue(family: EventFamily, type: String, build: PayloadBuilder.() -> Unit) {
        val rec = TraceRecord.obtain(family, type)
        PayloadBuilder(rec).build()
        if (!ring.offer(rec)) {
            overflowCount.incrementAndGet()
            rec.recycle()
            return
        }
        totalEmitted.incrementAndGet()
    }

    @PublishedApi
    internal fun enqueueEmpty(family: EventFamily, type: String) {
        val rec = TraceRecord.obtain(family, type)
        if (!ring.offer(rec)) {
            overflowCount.incrementAndGet()
            rec.recycle()
            return
        }
        totalEmitted.incrementAndGet()
    }

    fun overflowSnapshot(): Long = overflowCount.get()
    fun emittedSnapshot(): Long = totalEmitted.get()

    fun shutdown() {
        running.set(false)
        // Wake the writer immediately.
        LockSupport.unpark(writerThread)
        try {
            writerThread.join(2_000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /** Visible for tests. */
    fun awaitDrained(timeoutMs: Long = 2_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (drained.get() && ring.isEmpty) return true
            Thread.sleep(5)
        }
        return drained.get() && ring.isEmpty
    }

    private fun drainLoop() {
        var lastOverflowReportNs = android.os.SystemClock.elapsedRealtimeNanos()
        try {
            while (running.get()) {
                val rec = ring.poll()
                if (rec == null) {
                    LockSupport.parkNanos(parkNanos)
                    val now = android.os.SystemClock.elapsedRealtimeNanos()
                    if (now - lastOverflowReportNs >= overflowReportIntervalNanos) {
                        emitOverflowReport()
                        lastOverflowReportNs = now
                    }
                    continue
                }
                writeRecord(rec)
                rec.recycle()
            }
            // Drain remaining records on shutdown.
            while (true) {
                val rec = ring.poll() ?: break
                writeRecord(rec)
                rec.recycle()
            }
            emitOverflowReport()
            try {
                sink.flush()
                if (testSink == null) sink.close()
            } catch (_: Exception) {
            }
        } finally {
            drained.set(true)
        }
    }

    private fun writeRecord(rec: TraceRecord) {
        val line = lineBuffer
        line.setLength(0)
        line.append("{\"sid\":\"").append(header.sessionId)
            .append("\",\"tNs\":").append(rec.tNanos)
            .append(",\"th\":\"").append(escape(rec.thread))
            .append("\",\"fam\":\"").append(rec.family.name)
            .append("\",\"ev\":\"").append(rec.type).append('"')
        if (rec.payloadBuffer.isNotEmpty()) {
            line.append(rec.payloadBuffer)
        }
        line.append("}\n")
        try {
            sink.write(line.toString())
            bytesWrittenInCurrentFile += line.length
            // Phase 2 atrace markers — only meaningful for FRONTIER/RANGE/REBUFFER.
            maybeAtrace(rec)
            if (testSink == null && bytesWrittenInCurrentFile >= rotationBytes) {
                rotate()
            }
        } catch (_: Exception) {
            // swallow — instrumentation must never crash playback
        }
    }

    private fun maybeAtrace(rec: TraceRecord) {
        val traceEnabled = try {
            Trace.isEnabled()
        } catch (_: Throwable) {
            false
        }
        if (!traceEnabled) return
        if (!PlaybackTracer.enabled) return
        when (rec.family) {
            EventFamily.FRONTIER, EventFamily.RANGE, EventFamily.REBUFFER -> {
                val name = "nexio.${rec.family.name.lowercase()}.${rec.type}"
                val cookie = (rec.tNanos and 0x7fffffffL).toInt()
                Trace.beginAsyncSection(name, cookie)
                Trace.endAsyncSection(name, cookie)
            }
            else -> Unit
        }
    }

    private fun emitOverflowReport() {
        val dropped = overflowCount.getAndSet(0)
        if (dropped <= 0) return
        val line = StringBuilder(96)
            .append("{\"sid\":\"").append(header.sessionId)
            .append("\",\"tNs\":").append(android.os.SystemClock.elapsedRealtimeNanos())
            .append(",\"th\":\"").append(Thread.currentThread().name)
            .append("\",\"fam\":\"TRACER\",\"ev\":\"tracer_overflow\",\"droppedCount\":")
            .append(dropped).append("}\n")
        try {
            sink.write(line.toString())
            bytesWrittenInCurrentFile += line.length
        } catch (_: Exception) {
        }
    }

    private fun rotate() {
        try {
            sink.flush()
            sink.close()
        } catch (_: Exception) {
        }
        rotationIndex++
        bytesWrittenInCurrentFile = 0L
        sink = openFile(currentFile())
    }

    private fun currentFile(): File {
        val parent = baseFile?.parentFile
        val base = baseFile ?: return File("/dev/null")
        if (parent != null && !parent.exists()) parent.mkdirs()
        return if (rotationIndex == 0) base
        else File(parent, base.nameWithoutExtension + "-" + rotationIndex + "." + base.extension)
    }

    private fun openFile(file: File): Writer {
        return BufferedWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8), 8192)
    }

    private fun escape(s: String): String {
        // Thread name is friendly ASCII in practice; cheap escape only for quotes.
        if (s.indexOf('"') < 0 && s.indexOf('\\') < 0) return s
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
    }

    companion object {
        const val DEFAULT_CAPACITY: Int = 8192
        const val DEFAULT_ROTATION_BYTES: Long = 8L * 1024L * 1024L
        const val MAX_RETAINED_SESSIONS: Int = 20

        /**
         * Apply the "keep last 20 sessions" retention policy on the
         * `playback-traces` directory. Called from [PlaybackTracer.beginSession]
         * before opening a new file.
         */
        fun pruneOldSessions(dir: File) {
            if (!dir.exists() || !dir.isDirectory) return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
            if (files.size <= MAX_RETAINED_SESSIONS) return
            files.sortBy { it.lastModified() }
            val excess = files.size - MAX_RETAINED_SESSIONS
            for (i in 0 until excess) {
                runCatching { files[i].delete() }
            }
        }
    }
}
