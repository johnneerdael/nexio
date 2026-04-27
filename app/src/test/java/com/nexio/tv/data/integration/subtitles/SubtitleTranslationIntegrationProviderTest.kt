package com.nexio.tv.data.integration.subtitles

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransport
import com.nexio.tv.data.integration.subtitles.transport.SubtitleTranslationTransportResult
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleTranslationIntegrationProviderTest {
    @Test
    fun `execute routes through runtime call with subtitle translation policy bucket`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<SubtitleTranslationTransport>()
        val specSlot = slot<IntegrationCallSpec<SubtitleTranslationExecutionResult>>()

        coEvery { transport.execute(any()) } returns SubtitleTranslationTransportResult(
            statusCode = 200,
            body = """{"ok":true}""",
            isSuccessful = true,
            retryAfterHeader = "7",
            retryAfterHeaderMs = 7_000L
        )
        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                firstArg<IntegrationCallSpec<SubtitleTranslationExecutionResult>>().call()
            }
        }

        val provider = SubtitleTranslationIntegrationProvider(runtime, transport)
        val request = Request.Builder().url("https://translation.test/v1/chat/completions").build()

        val result = provider.execute(request) as IntegrationCallResult.Success
        val response = result.value as SubtitleTranslationExecutionResult.Response
        assertEquals(200, response.statusCode)
        assertEquals("""{"ok":true}""", response.body)
        assertTrue(response.isSuccessful)
        assertEquals("7", response.retryAfterHeader)
        assertEquals(7_000L, response.retryAfterHeaderMs)
        assertEquals(IntegrationProvider.SUBTITLE_TRANSLATION, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("subtitle:translation"), specSlot.captured.scope)
    }

    @Test
    fun `execute maps successful missing body to missing result`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<SubtitleTranslationTransport>()

        coEvery { transport.execute(any()) } returns SubtitleTranslationTransportResult(
            statusCode = 200,
            body = null,
            isSuccessful = true,
            retryAfterHeader = null,
            retryAfterHeaderMs = null
        )
        val specSlot = slot<IntegrationCallSpec<SubtitleTranslationExecutionResult>>()
        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                specSlot.captured.call()
            }
        }

        val provider = SubtitleTranslationIntegrationProvider(runtime, transport)
        val request = Request.Builder().url("https://translation.test/v1/messages").build()

        val result = provider.execute(request)
        assertTrue(result is IntegrationCallResult.Missing)
    }

    @Test
    fun `execute maps transport exceptions to network error result`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<SubtitleTranslationTransport>()
        val failure = IllegalStateException("boom")

        coEvery { transport.execute(any()) } throws failure
        val specSlot = slot<IntegrationCallSpec<SubtitleTranslationExecutionResult>>()
        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                specSlot.captured.call()
            }
        }

        val provider = SubtitleTranslationIntegrationProvider(runtime, transport)
        val request = Request.Builder().url("https://translation.test/v1/messages").build()

        val result = provider.execute(request)

        val network = result as IntegrationCallResult.NetworkError
        assertSame(failure, network.throwable)
        assertNull(network.retryAfterMs)
    }

    @Test
    fun `execute rethrows cancellation exception from transport`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<SubtitleTranslationTransport>()
        val cancellation = CancellationException("cancelled")

        coEvery { transport.execute(any()) } throws cancellation
        val specSlot = slot<IntegrationCallSpec<SubtitleTranslationExecutionResult>>()
        coEvery { runtime.call(capture(specSlot)) } answers {
            runBlocking {
                specSlot.captured.call()
            }
        }

        val provider = SubtitleTranslationIntegrationProvider(runtime, transport)
        val request = Request.Builder().url("https://translation.test/v1/messages").build()

        try {
            provider.execute(request)
        } catch (exception: CancellationException) {
            assertEquals(cancellation, exception)
            return@runTest
        }

        throw AssertionError("Expected CancellationException")
    }
}
