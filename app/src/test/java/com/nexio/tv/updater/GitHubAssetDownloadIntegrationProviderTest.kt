package com.nexio.tv.updater

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationStreamHandle
import com.nexio.tv.core.integration.IntegrationStreamSpec
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.github.GitHubAssetDownloadIntegrationProvider
import com.nexio.tv.data.integration.github.GitHubAssetDownloadStream
import com.nexio.tv.data.integration.github.transport.GitHubAssetDownloadTransport
import com.nexio.tv.data.integration.github.transport.GitHubAssetDownloadTransportResult
import java.io.ByteArrayInputStream
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubAssetDownloadIntegrationProviderTest {
    @Test
    fun `openDownload routes through runtime open with github stream spec`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<GitHubAssetDownloadTransport>()
        val specSlot = slot<IntegrationStreamSpec<GitHubAssetDownloadStream>>()
        val bodyBytes = "apk".toByteArray()

        every { transport.openDownloadStream(any()) } returns GitHubAssetDownloadTransportResult(
            inputStream = ByteArrayInputStream(bodyBytes),
            totalBytes = bodyBytes.size.toLong(),
            close = {}
        )
        coEvery { runtime.open(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationStreamSpec<GitHubAssetDownloadStream>>().open()
            }
        }

        val provider = GitHubAssetDownloadIntegrationProvider(runtime, transport)
        val handle = provider.openDownload("https://github.test/app.apk")

        assertNotNull(handle)
        val stream = handle!!.value
        assertEquals(bodyBytes.size.toLong(), stream.totalBytes)
        assertEquals("apk", String(stream.inputStream.readBytes()))
        assertEquals(IntegrationProvider.GITHUB, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("github:asset"), specSlot.captured.scope)
        handle.close()
    }

    @Test
    fun `openDownload maps zero content-length to zero`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<GitHubAssetDownloadTransport>()
        val specSlot = slot<IntegrationStreamSpec<GitHubAssetDownloadStream>>()

        every { transport.openDownloadStream(any()) } returns GitHubAssetDownloadTransportResult(
            inputStream = ByteArrayInputStream(byteArrayOf()),
            totalBytes = 0L,
            close = {}
        )
        coEvery { runtime.open(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationStreamSpec<GitHubAssetDownloadStream>>().open()
            }
        }

        val provider = GitHubAssetDownloadIntegrationProvider(runtime, transport)
        val handle = provider.openDownload("https://github.test/empty.apk")
        assertNotNull(handle)
        val stream = handle!!.value
        assertEquals(0L, stream.totalBytes)
        assertEquals(0, stream.inputStream.readBytes().size)
        handle.close()
    }

    @Test
    fun `openDownload maps unknown content-length to null`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<GitHubAssetDownloadTransport>()
        val specSlot = slot<IntegrationStreamSpec<GitHubAssetDownloadStream>>()
        val bodyBytes = "apk".toByteArray()

        every { transport.openDownloadStream(any()) } returns GitHubAssetDownloadTransportResult(
            inputStream = ByteArrayInputStream(bodyBytes),
            totalBytes = null,
            close = {}
        )
        coEvery { runtime.open(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationStreamSpec<GitHubAssetDownloadStream>>().open()
            }
        }

        val provider = GitHubAssetDownloadIntegrationProvider(runtime, transport)
        val handle = provider.openDownload("https://github.test/streaming.apk")
        assertNotNull(handle)
        val stream = handle!!.value
        assertNull(stream.totalBytes)
        assertEquals(3, stream.inputStream.readBytes().size)
        handle.close()
    }

    @Test
    fun `openDownload throws on http failure inside runtime stream path`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<GitHubAssetDownloadTransport>()
        val specSlot = slot<IntegrationStreamSpec<GitHubAssetDownloadStream>>()

        every { transport.openDownloadStream(any()) } throws IllegalStateException("Download failed: HTTP 503")
        coEvery { runtime.open(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationStreamSpec<GitHubAssetDownloadStream>>().open()
            }
        }

        val provider = GitHubAssetDownloadIntegrationProvider(runtime, transport)

        try {
            provider.openDownload("https://github.test/app.apk")
            org.junit.Assert.fail("Expected IllegalStateException")
        } catch (exception: IllegalStateException) {
            assertTrue(exception.message!!.contains("HTTP 503"))
        }
    }
}
