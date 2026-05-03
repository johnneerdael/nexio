package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCompanySearchResponse
import com.nexio.tv.data.remote.api.TmdbMultiSearchResponse
import com.nexio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nexio.tv.data.remote.api.TmdbPersonResponse
import com.nexio.tv.data.remote.api.TmdbPersonSearchResponse
import com.nexio.tv.data.local.TmdbCatalogPreferences
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TmdbIntegrationProviderRuntimeContractTest {

    @Test
    fun `loadPersonDetails routes through runtime get with CacheFirst PERSON_DETAIL apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbPersonResponse>(
            nextResult = IntegrationFetchResult.Updated(personDetailFixture())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.getPersonDetails(personId = 287, apiKey = "tmdb-key", language = null)
        } returns Response.success(personDetailFixture())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.loadPersonDetails(personId = 287)

        assertEquals(0, runtime.callSpecs.size)
        assertEquals(1, runtime.specs.size)
        val spec = runtime.specs.single()
        assertEquals(TmdbApiShapes.PERSON_DETAIL, spec.apiShapeId)
        assertEquals("tmdb.person.detail", spec.operationKey)
        assertTrue(
            "expected CacheFirst, was ${spec.cachePolicy}",
            spec.cachePolicy is IntegrationCachePolicy.CacheFirst
        )
    }

    @Test
    fun `loadPersonCombinedCredits routes through runtime get with CacheFirst PERSON_COMBINED_CREDITS apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbPersonCreditsResponse>(
            nextResult = IntegrationFetchResult.Updated(TmdbPersonCreditsResponse())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.getPersonCombinedCredits(personId = 287, apiKey = "tmdb-key", language = null)
        } returns Response.success(TmdbPersonCreditsResponse())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.loadPersonCombinedCredits(personId = 287)

        assertEquals(0, runtime.callSpecs.size)
        assertEquals(1, runtime.specs.size)
        val spec = runtime.specs.single()
        assertEquals(TmdbApiShapes.PERSON_COMBINED_CREDITS, spec.apiShapeId)
        assertEquals("tmdb.person.combined_credits", spec.operationKey)
        assertTrue(
            "expected CacheFirst, was ${spec.cachePolicy}",
            spec.cachePolicy is IntegrationCachePolicy.CacheFirst
        )
    }

    @Test
    fun `searchPeople routes through runtime call with SEARCH_PEOPLE apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbPersonSearchResponse>(
            nextCallResult = IntegrationCallResult.Success(TmdbPersonSearchResponse())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.searchPeople(apiKey = "tmdb-key", query = "Keanu", includeAdult = false)
        } returns Response.success(TmdbPersonSearchResponse())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.searchPeople(query = "Keanu")

        assertEquals(1, runtime.callSpecs.size)
        assertEquals(TmdbApiShapes.SEARCH_PEOPLE, runtime.callSpecs.single().apiShapeId)
    }

    @Test
    fun `searchCompanies routes through runtime call with SEARCH_COMPANIES apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbCompanySearchResponse>(
            nextCallResult = IntegrationCallResult.Success(TmdbCompanySearchResponse())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.searchCompanies(apiKey = "tmdb-key", query = "Pixar")
        } returns Response.success(TmdbCompanySearchResponse())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.searchCompanies(query = "Pixar")

        assertEquals(1, runtime.callSpecs.size)
        assertEquals(TmdbApiShapes.SEARCH_COMPANIES, runtime.callSpecs.single().apiShapeId)
    }

    @Test
    fun `searchMulti routes through runtime get with SEARCH_MULTI apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbMultiSearchResponse>(
            nextResult = IntegrationFetchResult.Updated(TmdbMultiSearchResponse())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.searchMulti(
                apiKey = "tmdb-key",
                query = "matrix",
                includeAdult = true,
                page = 2
            )
        } returns Response.success(TmdbMultiSearchResponse())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.searchMulti(
            query = "matrix",
            page = 2,
            preferences = TmdbCatalogPreferences(includeAdult = true)
        )

        assertEquals(0, runtime.callSpecs.size)
        assertEquals(1, runtime.specs.size)
        val spec = runtime.specs.single()
        assertEquals(TmdbApiShapes.SEARCH_MULTI, spec.apiShapeId)
        assertEquals("tmdb.search_multi", spec.operationKey)
        assertTrue(
            "expected Disabled, was ${spec.cachePolicy}",
            spec.cachePolicy is IntegrationCachePolicy.Disabled
        )
    }

    private fun personDetailFixture(): TmdbPersonResponse =
        TmdbPersonResponse(id = 287, name = "Brad Pitt")

    private fun buildProvider(
        runtime: RecordingIntegrationRuntime<*>,
        tmdbApi: TmdbApi
    ): TmdbIntegrationProvider =
        TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = {
                MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM)
            }
        )
}
