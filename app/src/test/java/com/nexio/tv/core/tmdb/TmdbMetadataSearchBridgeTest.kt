package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCompanySearchResponse
import com.nexio.tv.data.remote.api.TmdbCompanySearchResult
import com.nexio.tv.data.remote.api.TmdbPersonSearchResponse
import com.nexio.tv.data.remote.api.TmdbPersonSearchResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class TmdbMetadataSearchBridgeTest {

    @Test
    fun `findPersonIdByExactName accepts exact top result with accent normalization`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchPeople(
                apiKey = "test-api-key",
                query = "Nathalie Bienaime",
                page = 1,
                includeAdult = false
            )
        } returns Response.success(
            TmdbPersonSearchResponse(
                results = listOf(
                    TmdbPersonSearchResult(
                        id = 123,
                        name = "Nathalie Bienaimé"
                    )
                )
            )
        )

        val service = service(api)

        assertEquals(123, service.findPersonIdByExactName("Nathalie Bienaime"))
    }

    @Test
    fun `findPersonIdByExactName rejects non exact top result`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchPeople(
                apiKey = "test-api-key",
                query = "Trina Nishimura",
                page = 1,
                includeAdult = false
            )
        } returns Response.success(
            TmdbPersonSearchResponse(
                results = listOf(
                    TmdbPersonSearchResult(
                        id = 999,
                        name = "Trina N."
                    )
                )
            )
        )

        val service = service(api)

        assertNull(service.findPersonIdByExactName("Trina Nishimura"))
    }

    @Test
    fun `findCompanyIdByExactName accepts exact top result ignoring case`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchCompanies(
                apiKey = "test-api-key",
                query = "Dentsu",
                page = 1
            )
        } returns Response.success(
            TmdbCompanySearchResponse(
                results = listOf(
                    TmdbCompanySearchResult(
                        id = 1778,
                        name = "dentsu"
                    )
                )
            )
        )

        val service = service(api)

        assertEquals(1778, service.findCompanyIdByExactName("Dentsu"))
    }

    @Test
    fun `findCompanyIdByExactName rejects non exact top result`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchCompanies(
                apiKey = "test-api-key",
                query = "Production I.G",
                page = 1
            )
        } returns Response.success(
            TmdbCompanySearchResponse(
                results = listOf(
                    TmdbCompanySearchResult(
                        id = 166624,
                        name = "Production"
                    )
                )
            )
        )

        val service = service(api)

        assertNull(service.findCompanyIdByExactName("Production I.G"))
    }

    @Test
    fun `findPersonIdByExactName propagates cancellation`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchPeople(
                apiKey = "test-api-key",
                query = "Cancelled Person",
                page = 1,
                includeAdult = false
            )
        } throws CancellationException("person-cancelled")

        val service = service(api)

        try {
            service.findPersonIdByExactName("Cancelled Person")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("person-cancelled", exception.message)
        } catch (exception: Exception) {
            fail("Expected CancellationException, got ${exception::class.java}")
        }
    }

    @Test
    fun `findCompanyIdByExactName propagates cancellation`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.searchCompanies(
                apiKey = "test-api-key",
                query = "Cancelled Studio",
                page = 1
            )
        } throws CancellationException("company-cancelled")

        val service = service(api)

        try {
            service.findCompanyIdByExactName("Cancelled Studio")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("company-cancelled", exception.message)
        } catch (exception: Exception) {
            fail("Expected CancellationException, got ${exception::class.java}")
        }
    }

    private fun service(api: TmdbApi): TmdbMetadataService {
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        return TmdbMetadataService(
            appContext = mockk<Context>(relaxed = true),
            tmdbApi = api,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbCredentialProvider = {
                com.nexio.tv.core.metadata.MetadataProviderCredential(
                    apiKey = "test-api-key",
                    source = MetadataCredentialSource.CUSTOM
                )
            },
            metadataDiskCacheStore = metadataDiskCacheStore,
            integrationRuntime = passThroughTestRuntime(),
            ownershipFactory = IntegrationCacheOwnershipFactory(RailMediaIdentityResolver()),
            tmdbIntegrationProvider = TmdbIntegrationProvider(
                runtime = passThroughTestRuntime(),
                tmdbApi = api,
                tmdbCredentialProvider = {
                    com.nexio.tv.core.metadata.MetadataProviderCredential(
                        apiKey = "test-api-key",
                        source = MetadataCredentialSource.CUSTOM
                    )
                }
            )
        )
    }
}
