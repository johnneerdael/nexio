package com.nexio.tv.instrumentation

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Single source of truth for playback-trace control operations. Both the
 * UI ([com.nexio.tv.ui.screens.settings.PlaybackDiagnosticsSection]) and
 * the optional ADB [PlaybackTraceAdbReceiver] go through this controller —
 * keeps lifecycle, file handling, and toggle semantics in one place.
 *
 * Operations:
 *  - [setEnabled] — turn the tracer on or off (toggle-off mid-session calls
 *    [PlaybackTracer.endSession] for the current session via
 *    [PlaybackTraceToggle], so the JSONL writer flushes cleanly)
 *  - [refreshStatus] — re-read the on-disk trace files into [statusFlow]
 *  - [exportLast] / [exportAll] — build `Intent.ACTION_SEND` payloads via the
 *    app's [FileProvider]
 *  - [copyLastToDestination] — write the latest JSONL into a SAF-picked Uri
 *  - [clearAll] — delete every trace file under `playback-traces/`
 *
 * The controller never blocks on the calling thread; file I/O runs on
 * [Dispatchers.IO]. The status flow is a [StateFlow] so UI can collect it.
 */
@Singleton
class PlaybackTraceController @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val toggle: PlaybackTraceToggle,
) {

    /** Current toggle state, mirrored from [PlaybackTraceToggle.enabledFlow]. */
    val enabledFlow: Flow<Boolean> = toggle.enabledFlow

    private val _status = MutableStateFlow(TraceStatus.EMPTY)
    val statusFlow: StateFlow<TraceStatus> = _status.asStateFlow()

    /** Idempotent. Called from app startup so the tracer's file dir is set. */
    fun installFilesDirOnce() {
        PlaybackTracer.installFilesDir(appContext)
    }

    /**
     * Turn the tracer on or off. On toggle-off, [PlaybackTraceToggle] calls
     * [PlaybackTracer.endSession] for the active session (per spec §A.4)
     * so the JSONL writer flushes a final `playback_session_ended` and
     * closes the file before the boolean flag flips.
     */
    suspend fun setEnabled(enabled: Boolean) {
        toggle.setEnabled(enabled)
        refreshStatus()
    }

    /**
     * Re-read the trace directory from disk and publish a fresh
     * [TraceStatus]. Cheap; safe to call from a UI lifecycle observer.
     */
    suspend fun refreshStatus() = withContext(Dispatchers.IO) {
        val files = listTraces()
        val totalBytes = files.sumOf { it.length() }
        val lastFile = files.maxByOrNull { it.lastModified() }
        _status.value = TraceStatus(
            enabled = PlaybackTracer.enabled,
            sessionCount = countSessions(files),
            totalBytes = totalBytes,
            lastSessionFileName = lastFile?.name,
            lastSessionSizeBytes = lastFile?.length() ?: 0L,
        )
    }

    /** Build the share intent for the most recent JSONL. */
    suspend fun exportLast(): Intent? = withContext(Dispatchers.IO) {
        val last = listTraces().maxByOrNull { it.lastModified() } ?: return@withContext null
        buildShareIntentForFile(last)
    }

    /**
     * Build the share intent for a `.zip` containing every JSONL under
     * `playback-traces/`. The zip is written under `cacheDir/exports/` so
     * the system can clean it up.
     */
    suspend fun exportAll(): Intent? = withContext(Dispatchers.IO) {
        val files = listTraces()
        if (files.isEmpty()) return@withContext null
        val exportsDir = File(appContext.cacheDir, "playback-trace-exports").apply { mkdirs() }
        val zipFile = File(exportsDir, "playback-traces-${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            for (f in files) {
                zip.putNextEntry(ZipEntry(f.name))
                FileInputStream(f).use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        buildShareIntentForFile(zipFile)
    }

    /**
     * Copy the most recent JSONL into the SAF [destinationUri] picked via
     * `ACTION_CREATE_DOCUMENT`. Returns the byte count written.
     */
    suspend fun copyLastToDestination(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        val last = listTraces().maxByOrNull { it.lastModified() } ?: return@withContext 0L
        appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
            FileInputStream(last).use { it.copyTo(out) }
        } ?: return@withContext 0L
        return@withContext last.length()
    }

    /** Delete every JSONL under `playback-traces/`. Returns the number deleted. */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        val files = listTraces()
        var deleted = 0
        for (f in files) {
            if (f.delete()) deleted++
        }
        refreshStatus()
        deleted
    }

    /**
     * List every `.jsonl` file (including rotated parts like `<sid>-1.jsonl`)
     * in the playback-traces directory. Used by the controller and exposed
     * for tests.
     */
    fun listTraces(): List<File> {
        val dir = tracesDir() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.toList()
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Returns the on-disk trace directory, or null if not yet installed. */
    fun tracesDir(): File? {
        val dir = File(appContext.filesDir, "playback-traces")
        if (!dir.exists()) {
            // Lazily create so callers can rely on the directory existing
            // even if `installFilesDirOnce` was never invoked.
            dir.mkdirs()
        }
        return dir.takeIf { it.exists() }
    }

    private fun buildShareIntentForFile(file: File): Intent {
        val authority = "${appContext.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(appContext, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (file.name.endsWith(".zip")) "application/zip" else "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Group the trace files by session id (rotated parts of one session
     * share a prefix) so the status report shows distinct session count
     * rather than file count. Mirrors the pruning logic in
     * [SessionWriter.pruneOldSessions].
     */
    private fun countSessions(files: List<File>): Int {
        if (files.isEmpty()) return 0
        return files.map { f ->
            val name = f.nameWithoutExtension
            val dashIdx = name.lastIndexOf('-')
            if (dashIdx > 0 && name.substring(dashIdx + 1).all { it.isDigit() }) {
                name.substring(0, dashIdx)
            } else {
                name
            }
        }.toSet().size
    }
}

/**
 * Read-only snapshot of the trace directory state. Drives the status line
 * shown in [com.nexio.tv.ui.screens.settings.PlaybackDiagnosticsSection].
 */
data class TraceStatus(
    val enabled: Boolean,
    val sessionCount: Int,
    val totalBytes: Long,
    val lastSessionFileName: String?,
    val lastSessionSizeBytes: Long,
) {
    companion object {
        val EMPTY = TraceStatus(
            enabled = false,
            sessionCount = 0,
            totalBytes = 0L,
            lastSessionFileName = null,
            lastSessionSizeBytes = 0L,
        )
    }
}
