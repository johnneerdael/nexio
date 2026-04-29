package com.nexio.tv.data.integration.tmdb

import com.nexio.tv.core.integration.IntegrationCachePolicy
import com.nexio.tv.core.integration.IntegrationFetchResult
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.TmdbApiShapes
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCollectionResponse
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F2-C-01 pin: loadMovieCollection MUST route through IntegrationRuntime so it gains backoff,
 * single-flight, and audit coverage. Verify by asserting runtime.get is invoked with apiShapeId
 * equal to TmdbApiShapes.COLLECTION.
 */
class TmdbCollectionRuntimeCoverageTest {

    @Test
    fun `loadMovieCollection routes through runtime get with TmdbApiShapes COLLECTION`() = runTest {
        val runtime = RecordingIntegrationRuntime<TmdbCollectionResponse>(
            nextResult = IntegrationFetchResult.Updated(TmdbCollectionResponse(id = 10))
        )
        val tmdbApi = mockk<TmdbApi>(relaxed = true)

        val provider = buildProvider(runtime = runtime, tmdbApi = tmdbApi)

        provider.loadMovieCollection(collectionId = 10, normalizedLanguage = "en")

        assertEquals(0, runtime.callSpecs.size)
        assertEquals(1, runtime.specs.size)
        val spec = runtime.specs.single()
        assertEquals(TmdbApiShapes.COLLECTION, spec.apiShapeId)
        assertEquals("tmdb.collection", spec.operationKey)
        assertTrue(
            "expected CacheFirst, was ${spec.cachePolicy}",
            spec.cachePolicy is IntegrationCachePolicy.CacheFirst
        )
    }

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
