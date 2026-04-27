package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.data.integration.addon.AddonManifestIntegrationProvider
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.AddonManifestDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class AddonManifestIntegrationProviderTest {
    @Test
    fun `addon manifest provider routes addon manifest calls through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<AddonManifestDto>>()
        var runtimeResult: IntegrationCallResult<AddonManifestDto>? = null
        val manifestUrl = "https://addon.example/manifest.json"
        val dto = AddonManifestDto(
            id = "community.addon",
            name = "Community Addon",
            version = "1.0.0"
        )
        val callOrder = mutableListOf<String>()

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            callOrder += "runtime.call-enter"
            val result = specSlot.captured.call()
            callOrder += "runtime.call-exit"
            runtimeResult = result
            result
        }
        coEvery { addonApi.getManifest(manifestUrl) } coAnswers {
            callOrder += "addonApi.getManifest"
            Response.success(dto)
        }

        val provider = AddonManifestIntegrationProvider(runtime, addonApi)
        val result = provider.getManifest(
            addonId = "community.addon",
            manifestUrl = manifestUrl
        )

        assertTrue(result is NetworkResult.Success<*>)
        assertSame(dto, (result as NetworkResult.Success<*>).data)
        assertTrue(runtimeResult is IntegrationCallResult.Success<*>)
        assertEquals(IntegrationProvider.ADDON, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.ProviderConfig("addon:community.addon"), specSlot.captured.scope)
        assertEquals(listOf("runtime.call-enter", "addonApi.getManifest", "runtime.call-exit"), callOrder)
        coVerifyOrder {
            runtime.call(any<IntegrationCallSpec<AddonManifestDto>>())
            addonApi.getManifest(manifestUrl)
        }
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<AddonManifestDto>>())
            addonApi.getManifest(manifestUrl)
        }
    }

    @Test
    fun `addon manifest provider maps missing result to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val manifestUrl = "https://addon.example/manifest.json"

        coEvery { runtime.call(any<IntegrationCallSpec<AddonManifestDto>>()) } returns IntegrationCallResult.Missing

        val provider = AddonManifestIntegrationProvider(runtime, addonApi)
        val result = provider.getManifest(
            addonId = "community.addon",
            manifestUrl = manifestUrl
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(
            "Addon manifest request was skipped by runtime policy (blocked, backoff, or gate)",
            error.message
        )
        coVerify(exactly = 0) {
            addonApi.getManifest(any())
        }
    }

    @Test
    fun `addon manifest provider rethrows cancellation from api call`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<AddonManifestDto>>()
        val manifestUrl = "https://addon.example/manifest.json"
        val expected = CancellationException("cancelled")

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            specSlot.captured.call()
        }
        coEvery { addonApi.getManifest(manifestUrl) } coAnswers {
            throw expected
        }

        val provider = AddonManifestIntegrationProvider(runtime, addonApi)
        try {
            provider.getManifest(addonId = "community.addon", manifestUrl = manifestUrl)
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertSame(expected, exception)
        }
    }
}
