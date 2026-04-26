package com.nexio.tv.core.tmdb

import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbFindResponse
import com.nexio.tv.data.remote.api.TmdbFindResult
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImdbPosterLookupServiceTest {
    @Test
    fun `poster lookup resolves external id through tmdb integration runtime`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = TmdbFindResponse(
                movieResults = listOf(
                    TmdbFindResult(
                        id = 10,
                        posterPath = "/poster.jpg"
                    )
                )
            )
        )
        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = mockk<TmdbApi>(relaxed = true),
            tmdbCredentialProvider = {
                MetadataProviderCredential(
                    apiKey = "tmdb-key",
                    source = MetadataCredentialSource.CUSTOM
                )
            }
        )
        val service = ImdbPosterLookupService(provider)

        val posterUrl = service.lookupPosterUrl("tt1234567", titleType = "movie")

        assertEquals("https://image.tmdb.org/t/p/w154/poster.jpg", posterUrl)
        assertEquals(1, runtime.callSpecs.size)
        assertTrue(runtime.callSpecs.single().provider.name == "TMDB")
    }
}
