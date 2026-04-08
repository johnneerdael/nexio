package com.nexio.tv.instrumentation

import android.content.Context
import android.os.Trace
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

    /** Phase 2 atrace marker helpers (spec §G). Guarded against systrace cost. */
    fun beginAsyncSection(family: EventFamily, type: String, cookie: Int) {
        if (!enabled) return
        if (!Trace.isEnabled()) return
        Trace.beginAsyncSection("nexio.${family.name.lowercase()}.$type", cookie)
    }

    fun endAsyncSection(family: EventFamily, type: String, cookie: Int) {
        if (!enabled) return
        if (!Trace.isEnabled()) return
        Trace.endAsyncSection("nexio.${family.name.lowercase()}.$type", cookie)
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

/** Helper to splice the full session header into the start event payload. */
internal fun PayloadBuilder.putHeader(h: SessionHeader) {
    putString("sessionId", h.sessionId)
    putLong("startedAtNanos", h.startedAtNanos)
    putString("assetKeyHash", h.assetKeyHash)
    putString("serviceKey", h.serviceKey)
    putString("provider", h.provider)
    putString("benchmarkResultId", h.benchmarkResultId)
    putString("benchmarkSource", h.benchmarkSource)
    putBool("envelopePresent", h.envelopePresent)
    putBool("runtimeHintsPresent", h.runtimeHintsPresent)
    putString("specializationState", h.specializationState)
    putString("hintServiceKey", h.hintServiceKey)
    putString("hintHostScope", h.hintHostScope)
    putString("hintTransportClass", h.hintTransportClass)
    if (h.hintAgeMs != null) putLong("hintAgeMs", h.hintAgeMs)
    putString("hintFreshnessBand", h.hintFreshnessBand)
    putString("specializationMismatchReason", h.specializationMismatchReason)
    putString("observedHostScope", h.observedHostScope)
    putString("observedTransportClass", h.observedTransportClass)
    putString("branch", h.branch)
    putBool("cacheActive", h.cacheActive)
    putString("warmAheadFactory", h.warmAheadFactory)
    // FactoryArgs
    putLong("activeChunkBytes", h.factoryArgs.activeChunkBytes)
    putInt("parallelConnections", h.factoryArgs.parallelConnections)
    putLong("keepBehindBytes", h.factoryArgs.keepBehindBytes)
    putLong("bootstrapBytes", h.factoryArgs.bootstrapBytes)
    // Initial policy
    putInt("policy_urgentWorkers", h.initialPolicy.urgentWorkers)
    putInt("policy_prefetchWorkers", h.initialPolicy.prefetchWorkers)
    putLong("policy_urgentChunkBytes", h.initialPolicy.urgentChunkBytes)
    putLong("policy_prefetchChunkBytes", h.initialPolicy.prefetchChunkBytes)
    putString("policy_source", h.initialPolicy.source)
    // Client identity
    putString("playbackClientHash", h.clientIdentity.playbackClientHash)
    putInt("dispatcherMaxRequests", h.clientIdentity.dispatcherMaxRequests)
    putInt("dispatcherMaxRequestsPerHost", h.clientIdentity.dispatcherMaxRequestsPerHost)
    putInt("dispatcherQueuedCalls", h.clientIdentity.dispatcherQueuedCalls)
    putInt("dispatcherRunningCalls", h.clientIdentity.dispatcherRunningCalls)
    putInt("connectionPoolIdleCount", h.clientIdentity.connectionPoolIdleCount)
    putInt("connectionPoolTotalCount", h.clientIdentity.connectionPoolTotalCount)
    putLong("callTimeoutMs", h.clientIdentity.callTimeoutMs)
    putLong("readTimeoutMs", h.clientIdentity.readTimeoutMs)
    putLong("writeTimeoutMs", h.clientIdentity.writeTimeoutMs)
    putLong("connectTimeoutMs", h.clientIdentity.connectTimeoutMs)
    // Device
    putString("deviceModel", h.device.deviceModel)
    putString("deviceManufacturer", h.device.deviceManufacturer)
    putString("androidRelease", h.device.androidRelease)
    putInt("androidSdkInt", h.device.androidSdkInt)
    putString("appVersionName", h.device.appVersionName)
    putLong("appVersionCode", h.device.appVersionCode)
    putString("gitSha", h.device.gitSha)
    putInt("memoryClass", h.device.memoryClass)
    putInt("largeMemoryClass", h.device.largeMemoryClass)
    putBool("isLowRamDevice", h.device.isLowRamDevice)
    putString("networkType", h.device.networkType)
    putString("networkTransportHash", h.device.networkTransportHash)
}
