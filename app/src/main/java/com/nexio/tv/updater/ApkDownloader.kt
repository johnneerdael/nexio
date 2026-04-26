package com.nexio.tv.updater

import com.nexio.tv.data.integration.github.GitHubAssetDownloadIntegrationProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ApkDownloader @Inject constructor(
    private val githubAssetDownloadIntegrationProvider: GitHubAssetDownloadIntegrationProvider
) {

    suspend fun download(
        url: String,
        destinationFile: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit
    ): Result<File> {
        return try {
            destinationFile.parentFile?.mkdirs()

            val handle = githubAssetDownloadIntegrationProvider.openDownload(url)
                ?: error("Unable to open download stream")
            val handleClosed = AtomicBoolean(false)
            val handleClose: () -> Unit = {
                if (handleClosed.compareAndSet(false, true)) {
                    runCatching { handle.close() }
                }
            }
            val job = currentCoroutineContext()[Job]!!
            val cancellationHook = job.invokeOnCompletion { cause ->
                if (cause is CancellationException) {
                    handleClose()
                }
            }
            var tempFile: File? = null
            var downloadError: Throwable? = null
            try {
                tempFile = File.createTempFile(
                    "nexio-apk-downloader",
                    ".part",
                    destinationFile.parentFile
                )
                val stream = handle.value
                var downloaded = 0L
                stream.inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, stream.totalBytes)
                        }
                        output.flush()
                    }
                }
                stream.totalBytes?.let { totalBytes ->
                    if (downloaded != totalBytes) {
                        error("Download incomplete: expected $totalBytes bytes, got $downloaded bytes")
                    }
                }

                job.ensureActive()
                moveTempFile(tempFile, destinationFile)
            } catch (exception: Throwable) {
                downloadError = when {
                    exception is CancellationException -> exception
                    job.isCancelled -> CancellationException("Download was cancelled")
                    else -> exception
                }
            } finally {
                cancellationHook.dispose()
                handleClose()
                val deleteResult = runCatching {
                    if (tempFile?.exists() == true && !tempFile.delete()) {
                        throw IOException("Unable to cleanup temporary download file ${tempFile.absolutePath}")
                    }
                }

                if (downloadError == null) {
                    deleteResult.getOrThrow()
                    // Ignore close errors after a successful download to avoid false failures.
                }
            }

                downloadError?.let { throw it }
            Result.success(destinationFile)
        } catch (cancellationException: CancellationException) {
            throw cancellationException
        } catch (exception: Throwable) {
            Result.failure(exception)
        }
    }

    private fun moveTempFile(tempFile: File, destinationFile: File) {
        val tempPath = tempFile.toPath()
        val destinationPath = destinationFile.toPath()

        try {
            Files.move(
                tempPath,
                destinationPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(tempPath, destinationPath, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
