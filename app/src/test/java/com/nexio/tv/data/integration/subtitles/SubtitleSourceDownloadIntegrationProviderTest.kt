package com.nexio.tv.data.integration.subtitles

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.subtitles.transport.SubtitleSourceDownloadTransport
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubtitleSourceDownloadIntegrationProviderTest {
    @Test
    fun `downloadText routes through runtime call with subtitle source provider`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = SubtitleSourceDownloadTransport(OkHttpClient())
        val server = MockWebServer()
        val specSlot = slot<IntegrationCallSpec<String>>()

        server.enqueue(MockResponse().setResponseCode(200).setBody("1\n00:00:01,000 --> 00:00:02,000\nHello"))
        server.start()
        try {
            coEvery { runtime.call(capture(specSlot)) } coAnswers {
                specSlot.captured.call()
            }

            val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
            val result = provider.downloadText(server.url("/subtitle.srt").toString())

            assertEquals(
                IntegrationCallResult.Success("1\n00:00:01,000 --> 00:00:02,000\nHello"),
                result
            )
            assertEquals(IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD, specSlot.captured.provider)
            assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
            assertEquals(IntegrationScope.Account("subtitle:source"), specSlot.captured.scope)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadText maps http failures to integration http error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = SubtitleSourceDownloadTransport(OkHttpClient())
        val server = MockWebServer()

        server.enqueue(MockResponse().setResponseCode(503).setBody("down"))
        server.start()
        val specSlot = slot<IntegrationCallSpec<String>>()
        try {
            coEvery { runtime.call(capture(specSlot)) } coAnswers {
                specSlot.captured.call()
            }

            val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
            val result = provider.downloadText(server.url("/subtitle.srt").toString())

            assertTrue(result is IntegrationCallResult.HttpError)
            result as IntegrationCallResult.HttpError
            assertEquals(503, result.statusCode)
            assertEquals("subtitle_source_download_failed", result.reason)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadText maps blank bodies to success`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = SubtitleSourceDownloadTransport(OkHttpClient())
        val server = MockWebServer()

        server.enqueue(MockResponse().setResponseCode(200).setBody("   "))
        server.start()
        val specSlot = slot<IntegrationCallSpec<String>>()
        try {
            coEvery { runtime.call(capture(specSlot)) } coAnswers {
                specSlot.captured.call()
            }

            val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
            val result = provider.downloadText(server.url("/subtitle.srt").toString())

            assertEquals(IntegrationCallResult.Success("   "), result)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadText maps missing response body to missing`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val okHttpClient = mockk<OkHttpClient>()
        val transport = SubtitleSourceDownloadTransport(okHttpClient)
        val call = mockk<Call>()
        val callbackSlot = slot<Callback>()

        every { okHttpClient.newCall(any()) } returns call
        val response = mockk<Response>(relaxed = true)

        every { response.isSuccessful } returns true
        every { response.body } returns null
        every { call.enqueue(capture(callbackSlot)) } answers {
            callbackSlot.captured.onResponse(call, response)
        }
        every { call.cancel() } returns Unit

        val specSlot = slot<IntegrationCallSpec<String>>()
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }

        val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
        val result = provider.downloadText("https://example.test/subtitle.srt")

        assertEquals(IntegrationCallResult.Missing, result)
    }

    @Test
    fun `downloadBytes routes through runtime call and preserves payload bytes`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = SubtitleSourceDownloadTransport(OkHttpClient())
        val server = MockWebServer()
        val specSlot = slot<IntegrationCallSpec<ByteArray>>()
        val expected = "1\n00:00:00,000 --> 00:00:01,000\nHello\n".toByteArray(StandardCharsets.ISO_8859_1)

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(expected))
        )
        server.start()
        try {
            coEvery { runtime.call(capture(specSlot)) } coAnswers {
                specSlot.captured.call()
            }

            val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
            val result = provider.downloadBytes(server.url("/subtitle.srt").toString())

            assertTrue(result is IntegrationCallResult.Success)
            result as IntegrationCallResult.Success
            assertTrue(expected.contentEquals(result.value))
            assertEquals(IntegrationProvider.SUBTITLE_SOURCE_DOWNLOAD, specSlot.captured.provider)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `downloadText rethrows cancellation`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<SubtitleSourceDownloadTransport>()
        val cancellation = CancellationException("cancelled")

        coEvery { transport.executeUrl(any(), any()) } throws cancellation
        val specSlot = slot<IntegrationCallSpec<String>>()
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }

        val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)

        try {
            provider.downloadText("https://example.test/subtitle.srt")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
            return@runTest
        }

        throw AssertionError("Expected CancellationException")
    }

    @Test
    fun `downloadText cancels blocked okhttp call promptly`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val okHttpClient = mockk<OkHttpClient>()
        val transport = SubtitleSourceDownloadTransport(okHttpClient)
        val call = mockk<Call>()
        val specSlot = slot<IntegrationCallSpec<String>>()
        val cancelLatch = CountDownLatch(1)

        every { okHttpClient.newCall(any()) } returns call
        every { call.enqueue(any()) } returns Unit
        every { call.cancel() } answers {
            cancelLatch.countDown()
        }
        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }

        val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val job = scope.async {
                provider.downloadText("https://example.test/subtitle.srt")
            }
            delay(50L)

            assertTrue(job.isActive)
            job.cancel()
            assertTrue(cancelLatch.await(1, TimeUnit.SECONDS))
            scope.cancel()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `downloadBytes forwards request headers to subtitle transport`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = SubtitleSourceDownloadTransport(OkHttpClient())
        val server = MockWebServer()
        val specSlot = slot<IntegrationCallSpec<ByteArray>>()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("payload")
        )
        server.start()
        try {
            coEvery { runtime.call(capture(specSlot)) } coAnswers {
                specSlot.captured.call()
            }

            val provider = SubtitleSourceDownloadIntegrationProvider(runtime, transport)
            val result = provider.downloadBytes(
                url = server.url("/subtitle.srt").toString(),
                headers = mapOf("Authorization" to "Bearer token", "X-Addon" to "stremio")
            )
            val request = server.takeRequest(1, TimeUnit.SECONDS)

            assertTrue(result is IntegrationCallResult.Success)
            requireNotNull(request)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            assertEquals("stremio", request.getHeader("X-Addon"))
        } finally {
            server.shutdown()
        }
    }
}
