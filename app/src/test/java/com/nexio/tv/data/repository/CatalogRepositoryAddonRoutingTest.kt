package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.addon.AddonCatalogIntegrationProvider
import com.nexio.tv.data.local.CatalogDiskCacheStore
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.data.remote.dto.CatalogResponseDto
import com.nexio.tv.data.remote.dto.MetaPreviewDto
import com.nexio.tv.domain.model.MetaPreview
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class CatalogRepositoryAddonRoutingTest {
    @Test
    fun `addon catalog provider routes addon catalog calls through integration runtime`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<CatalogResponseDto>>()
        var runtimeResult: IntegrationCallResult<CatalogResponseDto>? = null
        val catalogUrl = "https://addon.example/catalog/movie/trending.json"
        val dto = CatalogResponseDto(
            metas = listOf(
                MetaPreviewDto(
                    id = "tt0111161",
                    type = "movie",
                    name = "The Shawshank Redemption"
                )
            )
        )

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = specSlot.captured.call()
            runtimeResult = result
            result
        }
        coEvery { addonApi.getCatalog(catalogUrl) } returns Response.success(dto)

        val provider = AddonCatalogIntegrationProvider(runtime, addonApi)
        val result = provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = catalogUrl
        )

        assertTrue(result is NetworkResult.Success<*>)
        assertSame(dto, (result as NetworkResult.Success<*>).data)
        assertTrue(runtimeResult is IntegrationCallResult.Success<*>)
        assertEquals(IntegrationProvider.ADDON, specSlot.captured.provider)
        assertEquals(IntegrationWorkClass.USER_VISIBLE, specSlot.captured.workClass)
        assertEquals(IntegrationScope.Account("addon:community.addon"), specSlot.captured.scope)
        coVerify(exactly = 1) {
            runtime.call(any<IntegrationCallSpec<CatalogResponseDto>>())
            addonApi.getCatalog(catalogUrl)
        }
    }

    @Test
    fun `addon catalog provider maps missing result to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val catalogUrl = "https://addon.example/catalog/movie/trending.json"

        coEvery { runtime.call(any<IntegrationCallSpec<CatalogResponseDto>>()) } returns IntegrationCallResult.Missing

        val provider = AddonCatalogIntegrationProvider(runtime, addonApi)
        val result = provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = catalogUrl
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(
            "Addon catalog request was skipped by runtime policy (blocked, backoff, or gate)",
            error.message
        )
        coVerify(exactly = 0) {
            addonApi.getCatalog(any())
        }
    }

    @Test
    fun `addon catalog provider maps http error response to network error`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<CatalogResponseDto>>()
        val catalogUrl = "https://addon.example/catalog/movie/trending.json"
        val statusCode = 503
        val reason = "Service unavailable"

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = specSlot.captured.call()
            result
        }
        val response = mockk<Response<CatalogResponseDto>>()
        every { response.isSuccessful } returns false
        every { response.code() } returns statusCode
        every { response.message() } returns reason
        coEvery { addonApi.getCatalog(catalogUrl) } returns response

        val provider = AddonCatalogIntegrationProvider(runtime, addonApi)
        val result = provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = catalogUrl
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(statusCode, error.code)
        assertEquals(reason, error.message)
    }

    @Test
    fun `addon catalog provider maps api exception to network error with exception message`() = runTest {
        val runtime = mockk<IntegrationRuntime>()
        val addonApi = mockk<AddonApi>()
        val specSlot = slot<IntegrationCallSpec<CatalogResponseDto>>()
        val catalogUrl = "https://addon.example/catalog/movie/trending.json"
        val message = "Addon api failure"

        coEvery { runtime.call(capture(specSlot)) } coAnswers {
            val result = specSlot.captured.call()
            result
        }
        coEvery { addonApi.getCatalog(catalogUrl) } throws RuntimeException(message)

        val provider = AddonCatalogIntegrationProvider(runtime, addonApi)
        val result = provider.getCatalog(
            addonId = "community.addon",
            catalogUrl = catalogUrl
        )

        assertTrue(result is NetworkResult.Error)
        val error = result as NetworkResult.Error
        assertEquals(message, error.message)
        assertEquals(null, error.code)
    }

    @Test
    fun `catalog repository fetches catalog through addon integration provider and preserves mapping and cache write`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>(relaxed = true)
        val catalogUrl = "https://addon.example/catalog/movie/trending/genre=drama&skip=20.json"
        val dto = CatalogResponseDto(
            metas = listOf(
                MetaPreviewDto(
                    id = "tt0111161",
                    type = "movie",
                    name = "The Shawshank Redemption",
                    poster = "https://images.example/poster.jpg",
                    background = "https://images.example/background.jpg"
                )
            )
        )

        coEvery { posterResolver.getActiveProvider() } returns null
        every { posterResolver.apply(any<MetaPreview>(), null) } answers { firstArg() }
        every { diskCacheStore.read(any()) } returns null
        coEvery {
            provider.getCatalog(
                addonId = "community.addon",
                catalogUrl = catalogUrl
            )
        } returns NetworkResult.Success(dto)

        val repository = CatalogRepositoryImpl(
            addonCatalogIntegrationProvider = provider,
            posterRatingsUrlResolver = posterResolver,
            catalogDiskCacheStore = diskCacheStore
        )

        val emissions = repository.getCatalogCachedFirst(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 20,
            skipStep = 20,
            extraArgs = mapOf("genre" to "drama"),
            supportsSkip = true,
            allowNetworkRefresh = true
        ).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first() is NetworkResult.Loading)
        val success = emissions.last() as NetworkResult.Success
        val row = success.data
        assertEquals("community.addon", row.addonId)
        assertEquals("Trending", row.catalogName)
        assertEquals(1, row.items.size)
        assertEquals("The Shawshank Redemption", row.items.single().name)
        assertEquals("https://images.example/poster.jpg", row.items.single().poster)
        assertTrue(row.hasMore)
        assertEquals(1, row.currentPage)

        coVerify(exactly = 1) {
            provider.getCatalog(
                addonId = "community.addon",
                catalogUrl = catalogUrl
            )
        }
        io.mockk.verify(exactly = 1) {
            diskCacheStore.write(
                cacheKey = any(),
                row = row,
                catalogVersionHash = any()
            )
        }
    }

    @Test
    fun `catalog repository preserves provider error behavior when no cache is available`() = runTest {
        val provider = mockk<AddonCatalogIntegrationProvider>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCacheStore = mockk<CatalogDiskCacheStore>()
        val catalogUrl = "https://addon.example/catalog/movie/trending.json"

        coEvery { posterResolver.getActiveProvider() } returns null
        every { diskCacheStore.read(any()) } returns null
        coEvery {
            provider.getCatalog(
                addonId = "community.addon",
                catalogUrl = catalogUrl
            )
        } returns NetworkResult.Error("boom", 503)

        val repository = CatalogRepositoryImpl(
            addonCatalogIntegrationProvider = provider,
            posterRatingsUrlResolver = posterResolver,
            catalogDiskCacheStore = diskCacheStore
        )

        val emissions = repository.getCatalogCachedFirst(
            addonBaseUrl = "https://addon.example",
            addonId = "community.addon",
            addonName = "Community Addon",
            catalogId = "trending",
            catalogName = "Trending",
            type = "movie",
            skip = 0,
            skipStep = 20,
            extraArgs = emptyMap(),
            supportsSkip = true,
            allowNetworkRefresh = true
        ).toList()

        assertEquals(2, emissions.size)
        assertTrue(emissions.first() is NetworkResult.Loading)
        val error = emissions.last() as NetworkResult.Error
        assertEquals("boom", error.message)
        assertEquals(503, error.code)
        assertNotNull(error)
    }
}
