package com.nexio.tv.updater

import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.data.integration.github.GitHubAssetDownloadStream
import com.nexio.tv.data.integration.github.GitHubAssetDownloadIntegrationProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ApkDownloaderTest {

    @Test
    fun `download writes all bytes and emits progress`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong()
                )

            override fun close() {
                closed = true
            }
        }

        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        val progressEvents = mutableListOf<Pair<Long, Long?>>()

        val result = ApkDownloader(provider).download(
            url = "https://example.com/app.apk",
            destinationFile = destination
        ) { downloaded, total ->
            progressEvents += downloaded to total
        }.getOrThrow()

        assertEquals(bytes.size.toLong(), result.length())
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(listOf(bytes.size.toLong() to bytes.size.toLong()), progressEvents)
        assertEquals(true, closed)
        coVerify(exactly = 1) { provider.openDownload("https://example.com/app.apk") }
    }

    @Test
    fun `download propagates cancellation during openDownload`() = runTest {
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        val openStarted = CompletableDeferred<Unit>()
        coEvery { provider.openDownload("https://example.com/app.apk") } coAnswers {
            openStarted.complete(Unit)
            delay(Long.MAX_VALUE)
            error("Download should be cancelled before returning")
        }

        val deferred = async {
            ApkDownloader(provider).download(
                url = "https://example.com/app.apk",
                destinationFile = destination
            ) { _, _ -> }
        }

        openStarted.await()
        deferred.cancel()

        try {
            deferred.await()
            fail("Expected cancellation to propagate from download")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `download succeeds when handle close throws after successful move`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong()
                )

            override fun close() {
                closed = true
                throw IOException("Handle close failed")
            }
        }

        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        val result = ApkDownloader(provider).download(
            url = "https://example.com/app.apk",
            destinationFile = destination
        ) { _, _ -> }
            .getOrThrow()

        assertEquals(bytes.size.toLong(), result.length())
        assertArrayEquals(bytes, destination.readBytes())
        assertEquals(true, closed)
    }

    @Test
    fun `download preserves existing destination file when stream fails`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        val originalDestinationBytes = "old-apk".toByteArray()
        destination.writeBytes(originalDestinationBytes)
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = FailingInputStream(bytes, failAfterBytes = 5),
                    totalBytes = bytes.size.toLong()
                )

            override fun close() {
                closed = true
            }
        }

        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        val progressEvents = mutableListOf<Pair<Long, Long?>>()

        val result = ApkDownloader(provider).download(
            url = "https://example.com/app.apk",
            destinationFile = destination
        ) { downloaded, total ->
            progressEvents += downloaded to total
        }

        assertFalse(result.isSuccess)
        assertArrayEquals(originalDestinationBytes, destination.readBytes())
        assertEquals(listOf(5L to bytes.size.toLong()), progressEvents)
        assertTrue(closed)
    }

    @Test
    fun `download closes handle when temporary file creation fails`() = runTest {
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        val originalDestinationBytes = "old-apk".toByteArray()
        destination.writeBytes(originalDestinationBytes)
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream("download-bytes".toByteArray()),
                    totalBytes = 1L
                )

            override fun close() {
                closed = true
            }
        }

        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle
        mockkStatic(File::class)

        try {
            every {
                File.createTempFile(any(), any(), any())
            } throws IOException("Unable to create temporary file")

            val result = ApkDownloader(provider).download(
                url = "https://example.com/app.apk",
                destinationFile = destination
            ) { _, _ -> }

            assertTrue(result.isFailure)
        } finally {
            unmockkStatic(File::class)
        }

        assertArrayEquals(originalDestinationBytes, destination.readBytes())
        assertTrue(closed)
        coVerify(exactly = 1) { provider.openDownload("https://example.com/app.apk") }
    }

    @Test
    fun `download fails when bytes downloaded differ from total bytes`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        destination.delete()
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong() + 10L
                )

            override fun close() {
                closed = true
            }
        }

        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        val progressEvents = mutableListOf<Pair<Long, Long?>>()

        val result = ApkDownloader(provider).download(
            url = "https://example.com/app.apk",
            destinationFile = destination
        ) { downloaded, total ->
            progressEvents += downloaded to total
        }

        assertFalse(result.isSuccess)
        assertFalse(destination.exists())
        assertEquals(listOf(bytes.size.toLong() to (bytes.size.toLong() + 10L)), progressEvents)
        assertTrue(closed)
    }

    @Test
    fun `download replaces destination using atomic file move when supported`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong()
                )

            override fun close() {
                closed = true
            }
        }
        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        mockkStatic(Files::class)
        try {
            every {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } returns destination.toPath()

            val result = ApkDownloader(provider).download(
                url = "https://example.com/app.apk",
                destinationFile = destination
            ) { _, _ -> }
                .getOrThrow()

            assertEquals(destination.absolutePath, result.absolutePath)
            verify(exactly = 1) {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            verify(exactly = 0) {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING)
            }
            assertTrue(closed)
        } finally {
            unmockkStatic(Files::class)
        }
    }

    @Test
    fun `download preserves existing destination when move fails`() = runTest {
        val bytes = "download-bytes".toByteArray()
        val destination = File.createTempFile("nexio-apk-downloader", ".bin")
        val originalDestinationBytes = "old-apk".toByteArray()
        destination.writeBytes(originalDestinationBytes)
        destination.deleteOnExit()

        val provider = mockk<GitHubAssetDownloadIntegrationProvider>()
        var closed = false
        val streamHandle = object : IntegrationStreamHandle<GitHubAssetDownloadStream> {
            override val value: GitHubAssetDownloadStream =
                GitHubAssetDownloadStream(
                    inputStream = ByteArrayInputStream(bytes),
                    totalBytes = bytes.size.toLong()
                )

            override fun close() {
                closed = true
            }
        }
        coEvery { provider.openDownload("https://example.com/app.apk") } returns streamHandle

        mockkStatic(Files::class)
        try {
            every {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } throws AtomicMoveNotSupportedException(
                null,
                null,
                "Atomic move not supported"
            )
            every {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING)
            } throws IOException("Unable to move temporary download file")

            val result = ApkDownloader(provider).download(
                url = "https://example.com/app.apk",
                destinationFile = destination
            ) { _, _ -> }

            assertTrue(result.isFailure)
            assertArrayEquals(originalDestinationBytes, destination.readBytes())
            verify(exactly = 1) {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
            verify(exactly = 1) {
                Files.move(any(), any(), StandardCopyOption.REPLACE_EXISTING)
            }
            assertTrue(closed)
        } finally {
            unmockkStatic(Files::class)
        }
    }

    private class FailingInputStream(
        private val bytes: ByteArray,
        private val failAfterBytes: Int
    ) : InputStream() {

        private val delegate = ByteArrayInputStream(bytes)
        private var bytesRead = 0

        override fun read(): Int {
            return delegate.read().also { if (it != -1) bytesRead += 1 }
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (bytesRead >= failAfterBytes) {
                throw IOException("Simulated stream failure")
            }

            val maxRead = kotlin.math.min(len, failAfterBytes - bytesRead)
            if (maxRead <= 0) {
                return -1
            }

            val read = delegate.read(b, off, maxRead)
            if (read > 0) {
                bytesRead += read
            }
            return read
        }
    }
}
