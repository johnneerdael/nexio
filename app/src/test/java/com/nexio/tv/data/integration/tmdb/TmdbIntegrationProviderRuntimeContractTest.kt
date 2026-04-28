package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nexio.tv.data.remote.api.TmdbPersonResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TmdbIntegrationProviderRuntimeContractTest {

    @Test
    fun `loadPersonDetails routes through runtime call with PERSON_DETAIL apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbPersonResponse>(
            nextCallResult = IntegrationCallResult.Success(personDetailFixture())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.getPersonDetails(personId = 287, apiKey = "tmdb-key", language = null)
        } returns Response.success(personDetailFixture())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.loadPersonDetails(personId = 287)

        assertEquals(1, runtime.callSpecs.size)
        assertEquals(TmdbApiShapes.PERSON_DETAIL, runtime.callSpecs.single().apiShapeId)
    }

    @Test
    fun `loadPersonCombinedCredits routes through runtime call with PERSON_COMBINED_CREDITS apiShape`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbPersonCreditsResponse>(
            nextCallResult = IntegrationCallResult.Success(TmdbPersonCreditsResponse())
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        coEvery {
            tmdbApi.getPersonCombinedCredits(personId = 287, apiKey = "tmdb-key", language = null)
        } returns Response.success(TmdbPersonCreditsResponse())

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.loadPersonCombinedCredits(personId = 287)

        assertEquals(1, runtime.callSpecs.size)
        assertEquals(TmdbApiShapes.PERSON_COMBINED_CREDITS, runtime.callSpecs.single().apiShapeId)
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
