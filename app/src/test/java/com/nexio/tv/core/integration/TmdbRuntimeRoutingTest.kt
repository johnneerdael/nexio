package com.nexio.tv.core.integration

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TmdbRuntimeRoutingTest {
    @Test
    fun `tmdb enrichment returns fresh runtime cache before touching provider api`() = runTest {
        val runtime = RecordingIntegrationRuntime(successValue = expectedEnrichment())
        val api = mockk<TmdbApi>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCache = mockk<MetadataDiskCacheStore>(relaxed = true)
        coEvery { posterResolver.getActiveProvider() } returns null

        val service = service(api, posterResolver, diskCache, runtime)

        val result = service.fetchEnrichment("550", ContentType.MOVIE, "en-US")

        assertEquals(expectedEnrichment(), result)
        assertEquals(listOf("tmdb:movie:550:en-US:enrichment:native"), runtime.keys)
        assertEquals(
            IntegrationCacheOwnership.Media("movie:tmdb:550"),
            runtime.specs.single().ownership
        )
        coVerify(exactly = 0) { api.getMovieDetails(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { api.getTvDetails(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `tmdb enrichment propagates cancellation from provider load`() = runTest {
        val api = mockk<TmdbApi>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCache = mockk<MetadataDiskCacheStore>(relaxed = true)
        coEvery { posterResolver.getActiveProvider() } returns null
        coEvery { api.getMovieDetails(any(), any(), any(), any(), any()) } throws
            CancellationException("cancelled")

        val service = service(api, posterResolver, diskCache, passThroughTestRuntime())

        try {
            service.fetchEnrichment("550", ContentType.MOVIE, "en-US")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }

    @Test
    fun `season episodes propagate cancellation`() = runTest {
        val api = mockk<TmdbApi>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCache = mockk<MetadataDiskCacheStore>(relaxed = true)
        coEvery { api.getTvSeasonDetails(any(), any(), any(), any()) } throws
            CancellationException("cancelled")

        val service = service(api, posterResolver, diskCache, passThroughTestRuntime())

        try {
            service.fetchSeasonEpisodes(tvId = 550, seasonNumber = 1, language = "en-US")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }

    @Test
    fun `episode enrichment propagates cancellation`() = runTest {
        val api = mockk<TmdbApi>()
        val posterResolver = mockk<PosterRatingsUrlResolver>()
        val diskCache = mockk<MetadataDiskCacheStore>(relaxed = true)
        coEvery { api.getTvSeasonDetails(any(), any(), any(), any()) } throws
            CancellationException("cancelled")

        val service = service(api, posterResolver, diskCache, passThroughTestRuntime())

        try {
            service.fetchEpisodeEnrichment(
                tmdbId = "550",
                seasonNumbers = listOf(1),
                language = "en-US"
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
    }

    private fun expectedEnrichment(): TmdbEnrichment = TmdbEnrichment(
        localizedTitle = "Fight Club",
        description = "Cached description",
        genres = listOf("Drama"),
        backdrop = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
        logo = null,
        poster = "https://image.tmdb.org/t/p/w500/poster.jpg",
        directorMembers = emptyList(),
        writerMembers = emptyList(),
        castMembers = emptyList(),
        releaseInfo = "1999",
        rating = 8.8,
        runtimeMinutes = 139,
        director = emptyList(),
        writer = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        ageRating = "R",
        countries = listOf("US"),
        language = "en",
        collectionId = null,
        collectionName = null
    )

    private fun service(
        api: TmdbApi,
        posterResolver: PosterRatingsUrlResolver,
        diskCache: MetadataDiskCacheStore,
        runtime: IntegrationRuntime
    ): TmdbMetadataService {
        val credentialProvider = suspend {
            MetadataProviderCredential(
                apiKey = "tmdb-key",
                source = MetadataCredentialSource.CUSTOM
            )
        }
        return TmdbMetadataService(
            appContext = mockk<Context>(relaxed = true),
            tmdbApi = api,
            posterRatingsUrlResolver = posterResolver,
            tmdbCredentialProvider = credentialProvider,
            metadataDiskCacheStore = diskCache,
            integrationRuntime = runtime,
            ownershipFactory = IntegrationCacheOwnershipFactory(RailMediaIdentityResolver()),
            tmdbIntegrationProvider = TmdbIntegrationProvider(
                runtime = runtime,
                tmdbApi = api,
                tmdbCredentialProvider = credentialProvider
            )
        )
    }
}
