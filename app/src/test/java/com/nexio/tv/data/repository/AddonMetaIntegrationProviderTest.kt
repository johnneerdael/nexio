package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.integration.addon.AddonMetaIntegrationProvider
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.MetaDto
import com.nexio.tv.data.remote.dto.MetaResponseDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AddonMetaIntegrationProviderTest {
    @Test
    fun `addon meta provider routes addon meta calls through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<MetaResponseDto>>()
        var runtimeResult: IntegrationCallResult<MetaResponseDto>? = null
        val metaUrl = "https://addon.example/meta/movie/tt123.json"
        val callOrder = mutableListOf<String>()
        val dto = MetaResponseDto(
            meta = MetaDto(
                id = "tt123",
                type = "movie",
                name = "The Example Movie"
            )
        )

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            callOrder += "runtime.call-enter"
            val result = specSlot.captured.call()
            callOrder += "runtime.call-exit"
            runtimeResult = result
            result
        }
        coEvery { addonApi.getMeta(metaUrl) } coAnswers {
            callOrder += "addonApi.getMeta"
            Response.success(dto)
        }

        val provider = AddonMetaIntegrationProvider(runtime, addonApi)
        val result = provider.getMeta(
            addonId = "community.addon",
            metaUrl = metaUrl
        )

        assertTrue(result is NetworkResult.Success<*>)
        assertSame(dto, (result as NetworkResult.Success<*>).data)
        assertTrue(runtimeResult is IntegrationCallResult.Success<*>)
        assertEquals(IntegrationProvider.ADDON, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("addon:community.addon"), specSlot.captured.scope)
        assertEquals(listOf("runtime.call-enter", "addonApi.getMeta", "runtime.call-exit"), callOrder)
        coVerifyOrder {
            runtime.call(any<IntegrationCallSpec<MetaResponseDto>>())
            addonApi.getMeta(metaUrl)
        }
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<MetaResponseDto>>())
            addonApi.getMeta(metaUrl)
        }
    }

    @Test
    fun `addon meta provider maps missing result to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val metaUrl = "https://addon.example/meta/movie/tt123.json"

        coEvery { runtime.call(any<IntegrationCallSpec<MetaResponseDto>>()) } returns IntegrationCallResult.Missing

        val provider = AddonMetaIntegrationProvider(runtime, addonApi)
        val result = provider.getMeta(
            addonId = "community.addon",
            metaUrl = metaUrl
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(
            "Addon meta request was skipped by runtime policy (blocked, backoff, or gate)",
            error.message
        )
        coVerify(exactly = 0) {
            addonApi.getMeta(any())
        }
    }
}
