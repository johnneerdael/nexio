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
    internal data class FileSnapshot(
        val originalFile: File,
        val snapshotFile: File,
    )

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
        if (!running.get()) return
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
        if (!running.get()) return
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

    internal fun snapshotIfCurrentFile(requestedFile: File, snapshotDir: File): FileSnapshot? {
        if (testSink != null || baseFile == null) return null
        synchronized(this) {
            val current = currentFile()
            if (requestedFile.absoluteFile != current.absoluteFile) return null
            if (!snapshotDir.exists()) snapshotDir.mkdirs()
            val snapshotRunDir = File(
                snapshotDir,
                "snapshot-" + android.os.SystemClock.elapsedRealtimeNanos()
            ).apply { mkdirs() }
            val snapshot = File(snapshotRunDir, current.name)
            try {
                sink.flush()
                current.inputStream().use { input ->
                    snapshot.outputStream().buffered().use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (_: Exception) {
                snapshot.delete()
                return null
            }
            return FileSnapshot(
                originalFile = current,
                snapshotFile = snapshot,
            )
        }
    }

    private fun drainLoop() {
        try {
            // Initialised inside the try so any unexpected throw from the
            // initial clock read still lands in the finally and lets
            // `awaitDrained` make progress.
            var lastOverflowReportNs = android.os.SystemClock.elapsedRealtimeNanos()
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
        synchronized(this) {
            val line = lineBuffer
            line.setLength(0)
            line.append("{\"sid\":\"").append(header.sessionId)
                .append("\",\"tNs\":").append(rec.tNanos)
                .append(",\"th\":\"").append(escape(rec.thread))
                .append("\",\"fam\":\"").append(rec.family.name)
                // `rec.type` is a constant string from EventFamily emit call sites
                // in production, but escape it defensively so a future caller
                // passing a quote-containing type cannot break the JSON.
                .append("\",\"ev\":\"").append(escape(rec.type)).append('"')
            if (rec.payloadBuffer.isNotEmpty()) {
                line.append(rec.payloadBuffer)
            }
            line.append("}\n")
            try {
                // Append the StringBuilder directly to avoid the per-record
                // String allocation `line.toString()` would incur on the writer
                // thread. `Writer.append(CharSequence)` is a standard method.
                sink.append(line)
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
    }

    private fun maybeAtrace(rec: TraceRecord) {
        // The async-section APIs require API 29; on older devices the markers
        // are silently skipped — Phase 2 system tracing is only useful on
        // newer Fire TV / Google TV hardware anyway.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) return
        val traceEnabled = try {
            Trace.isEnabled()
        } catch (_: Throwable) {
            false
        }
        if (!traceEnabled) return
        if (!PlaybackTracer.enabled) return
        when (rec.family) {
            EventFamily.FRONTIER, EventFamily.RANGE, EventFamily.REBUFFER -> {
                // Point event: post-hoc Perfetto correlation joins on
                // (sessionId, tNanos) so a zero-duration async span would
                // be misleading. `Trace.beginSection`/`endSection` would also
                // not span the producer-thread cost — these markers run on
                // the writer thread when the record is drained.
                val name = "nexio.${rec.family.name.lowercase()}.${rec.type}"
                Trace.beginSection(name)
                Trace.endSection()
            }
            else -> Unit
        }
    }

    private fun emitOverflowReport() {
        val dropped = overflowCount.getAndSet(0)
        if (dropped <= 0) return
        synchronized(this) {
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

    /**
     * Returns the file the writer should open for this rotation index. Only
     * called when [baseFile] is non-null — the test path constructs the
     * writer with `testSink` and short-circuits the file open at line 45,
     * so this method is only reachable on the production file-backed path.
     */
    private fun currentFile(): File {
        val base = baseFile
            ?: error("currentFile() called without a baseFile — test paths must use testSink")
        val parent = base.parentFile
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
         *
         * Files are grouped by session id (everything up to the first `-` or
         * `.` after the UUID) so a single long session that rotated into
         * `<sid>.jsonl`, `<sid>-1.jsonl`, `<sid>-2.jsonl` etc. counts as one
         * retained session, not three. Without this, a single 30-min session
         * with 4 rotations would consume 5 of the 20 retention slots and
         * could evict the older parts of itself mid-stream.
         */
        fun pruneOldSessions(dir: File) {
            if (!dir.exists() || !dir.isDirectory) return
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return
            // Group by session id. Filename shapes:
            //   <sid>.jsonl              → session id = <sid>
            //   <sid>-1.jsonl            → session id = <sid>
            //   <sid>-12.jsonl           → session id = <sid>
            val groups: Map<String, List<File>> = files.groupBy { f ->
                val name = f.nameWithoutExtension
                val dashIdx = name.lastIndexOf('-')
                if (dashIdx > 0 && name.substring(dashIdx + 1).all { it.isDigit() }) {
                    name.substring(0, dashIdx)
                } else {
                    name
                }
            }
            if (groups.size <= MAX_RETAINED_SESSIONS) return
            // Sort sessions by the most recent mtime within each group, so the
            // active session (still being written) is always retained.
            val sorted = groups.entries.sortedBy { entry ->
                entry.value.maxOf { it.lastModified() }
            }
            val excess = sorted.size - MAX_RETAINED_SESSIONS
            for (i in 0 until excess) {
                sorted[i].value.forEach { f -> runCatching { f.delete() } }
            }
        }
    }
}
