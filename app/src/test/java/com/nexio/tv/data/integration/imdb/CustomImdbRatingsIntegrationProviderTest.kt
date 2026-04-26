package com.nexio.tv.data.integration.imdb

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.data.integration.imdb.transport.CustomImdbRatingsTransport
import com.nexio.tv.data.integration.imdb.transport.CustomImdbTransportResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CustomImdbRatingsIntegrationProviderTest {

    @Test
    fun `execute runs OkHttp inside runtime call and maps success payload`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<CustomImdbRatingsTransport>()
        val request = Request.Builder().url("https://ratings.example.com/v1/ratings/tt1234567").build()
        val specSlot = slot<IntegrationCallSpec<CustomImdbPayload>>()
        val events = mutableListOf<String>()
        var runtimeResult: IntegrationCallResult<CustomImdbPayload>? = null

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            events += "runtime.call-enter"
            val result = specSlot.captured.call()
            events += "runtime.call-exit"
            runtimeResult = result
            result
        }
        coEvery { transport.execute(request) } answers {
            events += "transport.execute"
            CustomImdbTransportResult(
                statusCode = 200,
                isSuccessful = true,
                body = """{"results":[]}"""
            )
        }

        val provider = CustomImdbRatingsIntegrationProvider(runtime, transport)
        val result = provider.execute(baseUrl = "https://ratings.example.com", request = request)

        assertTrue(result is IntegrationCallResult.Success)
        assertEquals("""{"results":[]}""", (result as IntegrationCallResult.Success).value.body)
        assertTrue(runtimeResult is IntegrationCallResult.Success)
        assertEquals(
            listOf("runtime.call-enter", "transport.execute", "runtime.call-exit"),
            events
        )
        assertEquals(IntegrationProvider.CUSTOM_IMDB, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(
            IntegrationScope.Account("custom-imdb:https://ratings.example.com"),
            specSlot.captured.scope
        )
        coVerify(exactly = 1) { runtime.call(any<IntegrationCallSpec<CustomImdbPayload>>()) }
        coVerify(exactly = 1) { transport.execute(request) }
    }

    @Test
    fun `execute maps 429 retry after header to retryAfterMs`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<CustomImdbRatingsTransport>()
        val request = Request.Builder().url("https://ratings.example.com/v1/ratings/tt1234567").build()
        val specSlot = slot<IntegrationCallSpec<CustomImdbPayload>>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }
        coEvery { transport.execute(request) } returns CustomImdbTransportResult(
            statusCode = 429,
            isSuccessful = false,
            retryAfterMs = 12_000L
        )

        val provider = CustomImdbRatingsIntegrationProvider(runtime, transport)
        val result = provider.execute(baseUrl = "https://ratings.example.com", request = request)

        assertTrue(result is IntegrationCallResult.HttpError)
        result as IntegrationCallResult.HttpError
        assertEquals(429, result.statusCode)
        assertEquals(12_000L, result.retryAfterMs)
        assertEquals("custom_imdb_http_429", result.reason)
    }

    @Test
    fun `execute defaults retryAfterMs when 429 header is missing`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<CustomImdbRatingsTransport>()
        val request = Request.Builder().url("https://ratings.example.com/v1/ratings/tt1234567").build()
        val specSlot = slot<IntegrationCallSpec<CustomImdbPayload>>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }
        coEvery { transport.execute(request) } returns CustomImdbTransportResult(
            statusCode = 429,
            isSuccessful = false,
            retryAfterMs = 1_000L
        )

        val provider = CustomImdbRatingsIntegrationProvider(runtime, transport)
        val result = provider.execute(baseUrl = "https://ratings.example.com", request = request)

        assertTrue(result is IntegrationCallResult.HttpError)
        assertEquals(1_000L, (result as IntegrationCallResult.HttpError).retryAfterMs)
    }

    @Test
    fun `execute maps network failure to NetworkError`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<CustomImdbRatingsTransport>()
        val request = Request.Builder().url("https://ratings.example.com/v1/ratings/tt1234567").build()
        val specSlot = slot<IntegrationCallSpec<CustomImdbPayload>>()
        val expected = IOException("socket closed")

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }
        coEvery { transport.execute(request) } throws expected

        val provider = CustomImdbRatingsIntegrationProvider(runtime, transport)
        val result = provider.execute(baseUrl = "https://ratings.example.com", request = request)

        assertTrue(result is IntegrationCallResult.NetworkError)
        assertSame(expected, (result as IntegrationCallResult.NetworkError).throwable)
    }

    @Test
    fun `execute forwards runtime missing unchanged`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val transport = mockk<CustomImdbRatingsTransport>(relaxed = true)
        val request = Request.Builder().url("https://ratings.example.com/v1/ratings/tt1234567").build()

        coEvery { runtime.call(any<IntegrationCallSpec<CustomImdbPayload>>()) } returns IntegrationCallResult.Missing

        val provider = CustomImdbRatingsIntegrationProvider(runtime, transport)
        val result = provider.execute(baseUrl = "https://ratings.example.com", request = request)

        assertEquals(IntegrationCallResult.Missing, result)
        coVerify(exactly = 0) { transport.execute(any()) }
    }
}
