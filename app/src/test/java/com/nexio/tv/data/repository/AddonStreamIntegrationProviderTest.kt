package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.integration.addon.AddonStreamIntegrationProvider
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.api.StreamSearchRequestTag
import com.nexio.tv.data.remote.dto.StreamDto
import com.nexio.tv.data.remote.dto.StreamResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AddonStreamIntegrationProviderTest {
    @Test
    fun `addon stream provider routes stream calls through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<StreamResponseDto>>()
        var runtimeResult: IntegrationCallResult<StreamResponseDto>? = null
        val streamUrl = "https://addon.example/stream/movie/tt123.json"
        val requestTag = StreamSearchRequestTag("request-123")
        val callOrder = mutableListOf<String>()
        val dto = StreamResponseDto(streams = listOf(StreamDto(name = "A")))

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            callOrder += "runtime.call-enter"
            val result = specSlot.captured.call()
            callOrder += "runtime.call-exit"
            runtimeResult = result
            result
        }
        coEvery { addonApi.getStreams(streamUrl, requestTag) } coAnswers {
            callOrder += "addonApi.getStreams"
            Response.success(dto)
        }

        val provider = AddonStreamIntegrationProvider(runtime, addonApi)
        val result = provider.getStreams(
            addonId = "community.addon",
            streamUrl = streamUrl,
            requestTag = requestTag
        )

        assertTrue(result is NetworkResult.Success<*>)
        assertSame(dto, (result as NetworkResult.Success<*>).data)
        assertTrue(runtimeResult is IntegrationCallResult.Success<*>)
        assertEquals(IntegrationProvider.ADDON, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.Account("addon:community.addon"), specSlot.captured.scope)
        assertEquals(listOf("runtime.call-enter", "addonApi.getStreams", "runtime.call-exit"), callOrder)
        coVerifyOrder {
            runtime.call(any<IntegrationCallSpec<StreamResponseDto>>())
            addonApi.getStreams(streamUrl, requestTag)
        }
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<StreamResponseDto>>())
            addonApi.getStreams(streamUrl, requestTag)
        }
    }

    @Test
    fun `addon stream provider maps runtime missing result to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()

        coEvery { runtime.call(any<IntegrationCallSpec<StreamResponseDto>>()) } returns IntegrationCallResult.Missing

        val provider = AddonStreamIntegrationProvider(runtime, addonApi)
        val result = provider.getStreams(
            addonId = "community.addon",
            streamUrl = "https://addon.example/stream/movie/tt123.json",
            requestTag = StreamSearchRequestTag("request-123")
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(
            "Addon stream request was skipped by runtime policy (blocked, backoff, or gate)",
            error.message
        )
        coVerify(exactly = 0) { addonApi.getStreams(any(), any()) }
    }

    @Test
    fun `addon stream provider maps transport failures to network errors`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()

        coEvery { runtime.call(any<IntegrationCallSpec<StreamResponseDto>>()) } coAnswers {
            firstArg<IntegrationCallSpec<StreamResponseDto>>().call()
        }
        coEvery { addonApi.getStreams(any(), any()) } returns Response.error(
            503,
            "Service unavailable".toResponseBody("text/plain".toMediaType())
        )

        val provider = AddonStreamIntegrationProvider(runtime, addonApi)
        val result = provider.getStreams(
            addonId = "community.addon",
            streamUrl = "https://addon.example/stream/movie/tt123.json",
            requestTag = StreamSearchRequestTag("request-123")
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(503, error.code)
        assertEquals("Response.error()", error.message)
    }
}
