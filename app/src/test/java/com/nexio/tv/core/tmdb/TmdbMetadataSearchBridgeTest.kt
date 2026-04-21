package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCompanySearchResponse
import com.nexio.tv.data.remote.api.TmdbCompanySearchResult
import com.nexio.tv.data.remote.api.TmdbPersonSearchResponse
import com.nexio.tv.data.remote.api.TmdbPersonSearchResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            metadataDiskCacheStore = metadataDiskCacheStore
        )
    }
}
