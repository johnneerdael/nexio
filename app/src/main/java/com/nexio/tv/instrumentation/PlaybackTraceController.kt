package com.nexio.tv.instrumentation

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 *  - [copyLastToDestination] / [copyAllToDestination] — write the latest JSONL
 *    or a ZIP of all traces into a SAF-picked Uri
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
    companion object {
        private const val TAG = "PlaybackTraceCtrl"
    }

    /** Current toggle state, mirrored from [PlaybackTraceToggle.enabledFlow]. */
    val enabledFlow: Flow<Boolean> = toggle.enabledFlow

    private val _status = MutableStateFlow(TraceStatus.EMPTY)
    val statusFlow: StateFlow<TraceStatus> = _status.asStateFlow()

    /**
     * Application-scoped scope for toggle writes. Using a scope tied to this
     * singleton (rather than the calling ViewModel's viewModelScope) ensures
     * that navigating away from Settings before the DataStore write completes
     * cannot cancel the operation and leave the toggle in a false-disabled state.
     */
    private val controllerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget variant of [setEnabled] for UI callers. Prefer this over
     * launching [setEnabled] in a ViewModel scope — the ViewModel may be
     * cleared before the DataStore write dispatches, silently losing the toggle.
     */
    fun setEnabledAsync(enabled: Boolean) {
        controllerScope.launch { setEnabled(enabled) }
    }

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
        Log.i(TAG, "setEnabled enabled=$enabled before=${PlaybackTracer.enabled}")
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
        Log.i(
            TAG,
            "refreshStatus enabled=${_status.value.enabled} sessions=${_status.value.sessionCount} " +
                "bytes=${_status.value.totalBytes} last=${_status.value.lastSessionFileName}"
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
        val zipFile = buildAllSessionsZip(files)
        buildShareIntentForFile(zipFile)
    }

    /**
     * Copy the most recent JSONL into the SAF [destinationUri] picked via
     * `ACTION_CREATE_DOCUMENT`. Returns the byte count written.
     */
    suspend fun copyLastToDestination(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        val last = listTraces().maxByOrNull { it.lastModified() } ?: return@withContext 0L
        exportStableFile(last) { exportFile ->
            appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
                FileInputStream(exportFile).use { it.copyTo(out) }
            } ?: return@exportStableFile 0L
            Log.i(
                TAG,
                "copyLastToDestination sourceCount=1 copiedBytes=${exportFile.length()} destinationScheme=${destinationUri.scheme}"
            )
            exportFile.length()
        }
    }

    /**
     * Copy the latest JSONL into public Downloads and return the expected adb-pull path.
     */
    suspend fun copyLastToDownloads(): String? = withContext(Dispatchers.IO) {
        val last = listTraces().maxByOrNull { it.lastModified() } ?: return@withContext null
        exportStableFile(last) { exportFile ->
            writeFileToDownloads(
                sourceFile = exportFile,
                displayName = last.name,
                mimeType = "application/json",
            )
        }
    }

    /**
     * Copy a ZIP containing every retained trace into the SAF [destinationUri]
     * picked via `ACTION_CREATE_DOCUMENT`. Returns the byte count written.
     */
    suspend fun copyAllToDestination(destinationUri: Uri): Long = withContext(Dispatchers.IO) {
        val files = listTraces()
        if (files.isEmpty()) return@withContext 0L
        exportStableFiles(files) { exportFiles ->
            val zipFile = buildAllSessionsZip(exportFiles)
            appContext.contentResolver.openOutputStream(destinationUri)?.use { out ->
                FileInputStream(zipFile).use { it.copyTo(out) }
            } ?: return@exportStableFiles 0L
            Log.i(
                TAG,
                "copyAllToDestination sourceCount=${files.size} zipBytes=${zipFile.length()} copiedBytes=${zipFile.length()} destinationScheme=${destinationUri.scheme}"
            )
            zipFile.length()
        }
    }

    /**
     * Copy the ZIP bundle of all retained sessions into public Downloads and return the
     * expected adb-pull path. Returns null if Downloads cannot be opened for writing.
     */
    suspend fun copyAllToDownloads(nowMs: Long = System.currentTimeMillis()): String? = withContext(Dispatchers.IO) {
        val files = listTraces()
        if (files.isEmpty()) return@withContext null
        exportStableFiles(files) { exportFiles ->
            val zipFile = buildAllSessionsZip(exportFiles, nowMs)
            writeFileToDownloads(
                sourceFile = zipFile,
                displayName = zipFile.name,
                mimeType = "application/zip",
            )
        }
    }

    /**
     * Copy a ZIP containing only the most recent session family into public Downloads.
     * This is the authoritative capture path when the caller wants one fresh playback run.
     */
    suspend fun copyLatestSessionToDownloads(nowMs: Long = System.currentTimeMillis()): String? = withContext(Dispatchers.IO) {
        val files = listLatestSessionFiles()
        if (files.isEmpty()) return@withContext null
        exportStableFiles(files) { exportFiles ->
            val zipFile = buildSessionsZip(
                files = exportFiles,
                displayName = suggestedLatestSessionExportFileName(nowMs)
            )
            writeFileToDownloads(
                sourceFile = zipFile,
                displayName = zipFile.name,
                mimeType = "application/zip",
            )
        }
    }

    /** Delete every JSONL under `playback-traces/`. Returns the number deleted. */
    suspend fun clearAll(): Int = withContext(Dispatchers.IO) {
        PlaybackTracer.endCurrentSessionIfAny()
        val files = listTraces()
        var deleted = 0
        for (f in files) {
            if (f.delete()) deleted++
        }
        clearExportArtifacts()
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

    internal fun listLatestSessionFiles(files: List<File> = listTraces()): List<File> {
        val newest = files.maxByOrNull { it.lastModified() } ?: return emptyList()
        val latestSessionStem = sessionStem(newest)
        return files.filter { file -> sessionStem(file) == latestSessionStem }
            .sortedBy { it.name }
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

    private fun buildAllSessionsZip(files: List<File>, nowMs: Long = System.currentTimeMillis()): File {
        return buildSessionsZip(files, suggestedAllExportFileName(nowMs))
    }

    private fun buildSessionsZip(files: List<File>, displayName: String): File {
        val exportsDir = File(appContext.cacheDir, "playback-trace-exports").apply { mkdirs() }
        val zipFile = File(exportsDir, displayName)
        ZipOutputStream(FileOutputStream(zipFile).buffered()).use { zip ->
            for (file in files) {
                zip.putNextEntry(ZipEntry(file.name))
                FileInputStream(file).use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return zipFile
    }

    private inline fun <T> exportStableFile(file: File, block: (File) -> T): T {
        val snapshot = activeSnapshotForExport(file)
        val exportFile = snapshot?.snapshotFile ?: file
        return try {
            block(exportFile)
        } finally {
            snapshot?.snapshotFile?.delete()
            snapshot?.snapshotFile?.parentFile?.delete()
        }
    }

    private inline fun <T> exportStableFiles(files: List<File>, block: (List<File>) -> T): T {
        if (files.isEmpty()) return block(files)
        val snapshots = files.mapNotNull { file -> activeSnapshotForExport(file) }
        val snapshotByOriginalPath = snapshots.associateBy { it.originalFile.absolutePath }
        val exportFiles = files.map { file ->
            snapshotByOriginalPath[file.absolutePath]?.snapshotFile ?: file
        }
        return try {
            block(exportFiles)
        } finally {
            snapshots.forEach { snapshot ->
                snapshot.snapshotFile.delete()
                snapshot.snapshotFile.parentFile?.delete()
            }
        }
    }

    private fun activeSnapshotForExport(file: File): SessionWriter.FileSnapshot? {
        val snapshotDir = File(appContext.cacheDir, "playback-trace-export-snapshots").apply { mkdirs() }
        return PlaybackTracer.snapshotCurrentTraceFile(file, snapshotDir)
    }

    private fun clearExportArtifacts() {
        File(appContext.cacheDir, "playback-trace-exports").deleteRecursively()
        File(appContext.cacheDir, "playback-trace-export-snapshots").deleteRecursively()
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

    internal fun suggestedAllExportFileName(nowMs: Long = System.currentTimeMillis()): String {
        return "playback-traces-$nowMs.zip"
    }

    internal fun suggestedLatestSessionExportFileName(nowMs: Long = System.currentTimeMillis()): String {
        return "playback-trace-latest-$nowMs.zip"
    }

    private fun sessionStem(file: File): String {
        val name = file.nameWithoutExtension
        val dashIdx = name.lastIndexOf('-')
        return if (dashIdx > 0 && name.substring(dashIdx + 1).all { it.isDigit() }) {
            name.substring(0, dashIdx)
        } else {
            name
        }
    }

    private fun writeFileToDownloads(
        sourceFile: File,
        displayName: String,
        mimeType: String,
    ): String? {
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
        }
        val destinationUri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            values
        ) ?: return null
        val copied = try {
            resolver.openOutputStream(destinationUri)?.use { out ->
                FileInputStream(sourceFile).use { input -> input.copyTo(out) }
            } != null
        } catch (_: Exception) {
            resolver.delete(destinationUri, null, null)
            return null
        }
        if (!copied) {
            resolver.delete(destinationUri, null, null)
            return null
        }
        Log.i(
            TAG,
            "writeFileToDownloads displayName=$displayName mimeType=$mimeType copiedBytes=${sourceFile.length()} destinationScheme=${destinationUri.scheme}"
        )
        return "/sdcard/Download/$displayName"
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
