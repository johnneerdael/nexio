package com.nexio.tv.instrumentation

import android.content.Context
import java.io.File

/**
 * Singleton hot-path entry for the correlated playback trace. Toggle-OFF
 * compiles via `inline` to a single `@JvmField` volatile read + early return.
 *
 * Spec §A.1. WP1 only owns the lifecycle and emit() surface — wiring into
 * the player/scheduler/frontier files happens in WP3-WP8.
 */
object PlaybackTracer {

    @JvmField
    @Volatile
    var enabled: Boolean = false

    @Volatile
    private var current: SessionWriter? = null

    /** Optional `filesDir` set at app startup so [beginSession] can open files. */
    @Volatile
    private var filesDir: File? = null

    /** Used by tests to inject a custom writer. */
    @Volatile
    internal var writerFactory: ((SessionHeader) -> SessionWriter)? = null

    fun installFilesDir(context: Context) {
        filesDir = File(context.filesDir, "playback-traces").apply { mkdirs() }
    }

    /** Test hook — allows replacement of the default writer factory. */
    internal fun installWriterFactory(factory: ((SessionHeader) -> SessionWriter)?) {
        writerFactory = factory
    }

    @Synchronized
    fun beginSession(header: SessionHeader): String {
        // End any prior session first — sessions are 1:1 with MediaSourceSession.
        current?.let {
            it.shutdown()
        }
        val writer = writerFactory?.invoke(header) ?: defaultWriter(header)
        current = writer
        // Emit the session start event with the full header.
        writer.enqueue(EventFamily.SESSION, "playback_session_started") {
            putHeader(header)
        }
        return header.sessionId
    }

    @Synchronized
    fun endSession(sessionId: String) {
        val w = current ?: return
        if (w.sessionId != sessionId) return
        val overflow = w.overflowSnapshot()
        val emitted = w.emittedSnapshot()
        w.enqueueEmpty(EventFamily.SESSION, "playback_session_ended")
        // Tail tracer_overflow report (best-effort).
        w.enqueue(EventFamily.TRACER, "tracer_overflow_summary") {
            putLong("droppedCount", overflow)
            putLong("totalEmitted", emitted)
        }
        w.shutdown()
        current = null
    }

    /** Hot-path entry. Toggle-off compiles to `if (false) ...`. */
    @Suppress("NOTHING_TO_INLINE")
    inline fun emit(family: EventFamily, type: String, noinline build: PayloadBuilder.() -> Unit) {
        if (!enabled) return
        val w = currentInternal() ?: return
        w.enqueue(family, type, build)
    }

    @Suppress("NOTHING_TO_INLINE")
    inline fun emit(family: EventFamily, type: String) {
        if (!enabled) return
        currentInternal()?.enqueueEmpty(family, type)
    }

    /** Visible for `inline` callers — do not use directly. */
    @PublishedApi
    internal fun currentInternal(): SessionWriter? = current

    @Synchronized
    internal fun endCurrentSessionIfAny(): String? {
        val active = current ?: return null
        endSession(active.sessionId)
        return active.sessionId
    }

    internal fun snapshotCurrentTraceFile(requestedFile: File, snapshotDir: File): SessionWriter.FileSnapshot? {
        return currentInternal()?.snapshotIfCurrentFile(requestedFile, snapshotDir)
    }

    private fun defaultWriter(header: SessionHeader): SessionWriter {
        val dir = filesDir
        val file = if (dir != null) {
            SessionWriter.pruneOldSessions(dir)
            File(dir, "${header.sessionId}.jsonl")
        } else null
        return SessionWriter(header, file)
    }
}

// `putHeader` extension lives next to its receiver in `PayloadBuilder.kt`
// (L3 cleanup). It is `internal` so call sites in this file still resolve it.
