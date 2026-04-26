package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCollectionPart
import com.nexio.tv.data.remote.api.TmdbCollectionResponse
import com.nexio.tv.data.remote.api.TmdbImagesResponse
import com.nexio.tv.data.remote.api.TmdbImage
import com.nexio.tv.domain.model.MetaPreview
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.every
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class TmdbMetadataServiceCollectionTest {
    @Test
    fun `fetchMovieCollection delegates to integration provider and maps collection part`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>()
        val integrationRuntime = mockk<IntegrationRuntime>(relaxed = true)
        val ownershipFactory = mockk<IntegrationCacheOwnershipFactory>(relaxed = true)
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>()

        coEvery { posterRatingsUrlResolver.getActiveProvider() } returns null
        every { posterRatingsUrlResolver.apply(any<MetaPreview>(), null) } answers { firstArg() }
        coEvery {
            tmdbIntegrationProvider.loadMovieCollection(
                collectionId = 900,
                normalizedLanguage = "en-US"
            )
        } returns IntegrationLoadResult.Success(
            TmdbCollectionResponse(
                id = 900,
                name = "Collection Name",
                parts = listOf(
                    TmdbCollectionPart(
                        id = 1001,
                        title = "Part 1",
                        overview = "Part one",
                        releaseDate = "2020-01-01",
                        posterPath = "/part1.jpg",
                        backdropPath = "/part1-backdrop.jpg",
                        voteAverage = 7.0
                    )
                )
            )
        )
        coEvery {
            tmdbIntegrationProvider.loadRecommendationImages(
                tmdbId = 1001,
                tmdbType = "movie",
                includeImageLanguage = "en,en-US,en,null"
            )
        } returns IntegrationLoadResult.Success(
            TmdbImagesResponse(
                backdrops = listOf(
                    TmdbImage(
                        filePath = "/collection-bg.jpg",
                        iso6391 = "en"
                    )
                )
            )
        )

        val service = TmdbMetadataService(
            appContext = context,
            tmdbApi = tmdbApi,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbCredentialProvider = {
                MetadataProviderCredential(
                    apiKey = "tmdb-key",
                    source = MetadataCredentialSource.CUSTOM
                )
            },
            metadataDiskCacheStore = metadataDiskCacheStore,
            integrationRuntime = integrationRuntime,
            ownershipFactory = ownershipFactory,
            tmdbIntegrationProvider = tmdbIntegrationProvider
        )

        val items = service.fetchMovieCollection(900, "en-US")

        assertEquals(1, items.size)
        assertEquals("Part 1", items.first().name)
        assertEquals("tmdb:1001", items.first().id)

        coVerify(exactly = 1) {
            tmdbIntegrationProvider.loadMovieCollection(
                collectionId = 900,
                normalizedLanguage = "en-US"
            )
        }
        coVerify(exactly = 1) {
            tmdbIntegrationProvider.loadRecommendationImages(
                tmdbId = 1001,
                tmdbType = "movie",
                includeImageLanguage = "en,en-US,en,null"
            )
        }
        coVerify(exactly = 0) { tmdbApi.getCollectionDetails(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieImages(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvImages(any(), any(), any()) }
    }

    @Test
    fun `fetchMovieCollection preserves cancellation from collection fetch`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>()
        val integrationRuntime = mockk<IntegrationRuntime>(relaxed = true)
        val ownershipFactory = mockk<IntegrationCacheOwnershipFactory>(relaxed = true)
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>()

        coEvery { posterRatingsUrlResolver.getActiveProvider() } returns null
        coEvery {
            tmdbIntegrationProvider.loadMovieCollection(
                collectionId = 900,
                normalizedLanguage = "en-US"
            )
        } throws CancellationException("collection-fetch")

        val service = TmdbMetadataService(
            appContext = context,
            tmdbApi = tmdbApi,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbCredentialProvider = {
                MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM)
            },
            metadataDiskCacheStore = metadataDiskCacheStore,
            integrationRuntime = integrationRuntime,
            ownershipFactory = ownershipFactory,
            tmdbIntegrationProvider = tmdbIntegrationProvider
        )

        try {
            service.fetchMovieCollection(900, "en-US")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("collection-fetch", exception.message)
        }
    }

    @Test
    fun `fetchMovieCollection preserves cancellation from recommendation image fetch`() = runTest {
        val context = mockk<Context>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>()
        val integrationRuntime = mockk<IntegrationRuntime>(relaxed = true)
        val ownershipFactory = mockk<IntegrationCacheOwnershipFactory>(relaxed = true)
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>()

        coEvery { posterRatingsUrlResolver.getActiveProvider() } returns null
        coEvery {
            tmdbIntegrationProvider.loadMovieCollection(
                collectionId = 901,
                normalizedLanguage = "en-US"
            )
        } returns IntegrationLoadResult.Success(
            TmdbCollectionResponse(
                id = 901,
                name = "Collection Name",
                parts = listOf(
                    TmdbCollectionPart(
                        id = 1001,
                        title = "Part One",
                        overview = null,
                        releaseDate = "2020-01-01",
                        posterPath = "/poster.jpg",
                        backdropPath = "/backdrop.jpg",
                        voteAverage = 8.0
                    )
                )
            )
        )
        coEvery {
            tmdbIntegrationProvider.loadRecommendationImages(
                tmdbId = 1001,
                tmdbType = "movie",
                includeImageLanguage = any()
            )
        } throws CancellationException("image-fetch")

        val service = TmdbMetadataService(
            appContext = context,
            tmdbApi = tmdbApi,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbCredentialProvider = {
                MetadataProviderCredential(
                    apiKey = "tmdb-key",
                    source = MetadataCredentialSource.CUSTOM
                )
            },
            metadataDiskCacheStore = metadataDiskCacheStore,
            integrationRuntime = integrationRuntime,
            ownershipFactory = ownershipFactory,
            tmdbIntegrationProvider = tmdbIntegrationProvider
        )

        try {
            service.fetchMovieCollection(901, "en-US")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("image-fetch", exception.message)
        }
    }
}
