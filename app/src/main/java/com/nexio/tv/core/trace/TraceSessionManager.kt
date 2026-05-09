package com.nexio.tv.core.trace

import com.google.gson.Gson
import com.nexio.tv.core.util.toHexLowercase
import java.io.File
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

data class TraceBuildInfo(
    val appVersion: String,
    val buildType: String,
    val gitSha: String?,
    val deviceModel: String,
    val androidVersion: String
)

@Singleton
class TraceSessionManager @Inject constructor(
    private val tracesRoot: File,
    private val gson: Gson,
    private val clock: () -> Long = System::currentTimeMillis,
    private val buildInfo: TraceBuildInfo
) {
    private data class State(val session: TraceSession, val sink: FileRuntimeTraceSink)

    private val state = AtomicReference<State?>(null)

    fun activeSession(): TraceSession? = state.get()?.session

    fun activeSink(): RuntimeTraceSink = state.get()?.sink ?: NoopRuntimeTraceSink

    fun start(mode: TraceMode, activeProfileHash: String?) {
        if (mode == TraceMode.OFF) return
        stop()
        val sessionId = UUID.randomUUID().toString()
        val sessionDir = File(tracesRoot, sessionId).apply { mkdirs() }
        val file = File(sessionDir, "trace-events.jsonl")
        val salt = randomSalt()
        val session = TraceSession(
            traceSessionId = sessionId,
            startedAtEpochMs = clock(),
            appVersion = buildInfo.appVersion,
            buildType = buildInfo.buildType,
            gitSha = buildInfo.gitSha,
            deviceModel = buildInfo.deviceModel,
            androidVersion = buildInfo.androidVersion,
            activeProfileHash = activeProfileHash,
            mode = mode,
            salt = salt
        )
        val sink = FileRuntimeTraceSink(
            sessionId = sessionId,
            writer = JsonlTraceWriter(file, gson, maxBytes = 50L * 1024 * 1024),
            redactor = TraceRedactor()
        )
        state.set(State(session, sink))
    }

    fun stop() {
        state.getAndSet(null)?.sink?.close()
    }

    private fun randomSalt(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.toHexLowercase()
    }
}
