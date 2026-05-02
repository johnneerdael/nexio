package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.integration.IntegrationCacheOwnershipFactory
import com.nexio.tv.core.integration.RailMediaIdentityResolver
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.integration.tmdb.DefaultTmdbExternalIdLookupProvider
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCreditsResponse
import com.nexio.tv.data.remote.api.TmdbDetailsResponse
import com.nexio.tv.data.remote.api.TmdbGenre
import com.nexio.tv.data.remote.api.TmdbImagesResponse
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDateCountry
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDateItem
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nexio.tv.data.remote.api.TmdbTvContentRatingItem
import com.nexio.tv.data.remote.api.TmdbTvContentRatingsResponse
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.PosterRatingsProvider
import com.nexio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

class TmdbMetadataPerformanceTest {

    @Test
    fun `fetchEnrichment avoids separate TMDB subresource requests when details include appended data`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val service = buildMetadataService(tmdbApi)

        coEvery {
            tmdbApi.getMovieDetails(
                movieId = 550,
                apiKey = "tmdb-key",
                language = "en-US",
                appendToResponse = "credits,images,release_dates,external_ids",
                includeImageLanguage = "en,en-US,en,null"
            )
        } returns Response.success(
            TmdbDetailsResponse(
                id = 550,
                title = "Fight Club",
                overview = "An insomniac office worker crosses paths with a soap maker.",
                genres = listOf(TmdbGenre(id = 18, name = "Drama")),
                voteAverage = 8.4,
                releaseDate = "1999-10-15",
                credits = TmdbCreditsResponse(cast = emptyList(), crew = emptyList()),
                images = TmdbImagesResponse(logos = emptyList(), backdrops = emptyList()),
                releaseDates = TmdbMovieReleaseDatesResponse(
                    results = listOf(
                        TmdbMovieReleaseDateCountry(
                            iso31661 = "US",
                            releaseDates = listOf(TmdbMovieReleaseDateItem(certification = "R"))
                        )
                    )
                )
            )
        )
        coEvery { tmdbApi.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { tmdbApi.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { tmdbApi.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())

        val enrichment = service.fetchEnrichment(
            tmdbId = "550",
            contentType = ContentType.MOVIE,
            language = "en-US"
        )

        assertNotNull(enrichment)
        assertEquals("Fight Club", enrichment?.localizedTitle)
        assertEquals("R", enrichment?.ageRating)
        coVerify(exactly = 1) {
            tmdbApi.getMovieDetails(
                movieId = 550,
                apiKey = "tmdb-key",
                language = "en-US",
                appendToResponse = "credits,images,release_dates,external_ids",
                includeImageLanguage = "en,en-US,en,null"
            )
        }
        coVerify(exactly = 0) { tmdbApi.getMovieCredits(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieImages(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieReleaseDates(any(), any()) }
    }

    @Test
    fun `fetchEnrichment appends tv external ids with detail metadata`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val service = buildMetadataService(tmdbApi)

        coEvery {
            tmdbApi.getTvDetails(
                tvId = 1399,
                apiKey = "tmdb-key",
                language = "en-US",
                appendToResponse = "credits,images,content_ratings,external_ids",
                includeImageLanguage = "en,en-US,en,null"
            )
        } returns Response.success(
            TmdbDetailsResponse(
                id = 1399,
                name = "Game of Thrones",
                overview = "Seven noble families fight for control.",
                genres = listOf(TmdbGenre(id = 18, name = "Drama")),
                voteAverage = 8.4,
                firstAirDate = "2011-04-17",
                credits = TmdbCreditsResponse(cast = emptyList(), crew = emptyList()),
                images = TmdbImagesResponse(logos = emptyList(), backdrops = emptyList()),
                contentRatings = TmdbTvContentRatingsResponse(
                    results = listOf(TmdbTvContentRatingItem(iso31661 = "US", rating = "TV-MA"))
                )
            )
        )

        val enrichment = service.fetchEnrichment(
            tmdbId = "1399",
            contentType = ContentType.SERIES,
            language = "en-US"
        )

        assertNotNull(enrichment)
        assertEquals("Game of Thrones", enrichment?.localizedTitle)
        assertEquals("TV-MA", enrichment?.ageRating)
        coVerify(exactly = 1) {
            tmdbApi.getTvDetails(
                tvId = 1399,
                apiKey = "tmdb-key",
                language = "en-US",
                appendToResponse = "credits,images,content_ratings,external_ids",
                includeImageLanguage = "en,en-US,en,null"
            )
        }
        coVerify(exactly = 0) { tmdbApi.getTvCredits(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvImages(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getTvContentRatings(any(), any()) }
    }

    @Test
    fun `fetchEnrichment does not expose tmdb poster when poster provider is active`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val activePosterProvider = PosterRatingsUrlResolver.ActiveProvider(
            provider = PosterRatingsProvider.RPDB,
            apiKey = "rpdb-key"
        )
        val service = buildMetadataService(tmdbApi, activePosterProvider = activePosterProvider)

        coEvery {
            tmdbApi.getMovieDetails(
                movieId = 550,
                apiKey = "tmdb-key",
                language = "en-US",
                appendToResponse = "credits,images,release_dates,external_ids",
                includeImageLanguage = "en,en-US,en,null"
            )
        } returns Response.success(
            TmdbDetailsResponse(
                id = 550,
                title = "Fight Club",
                overview = "An insomniac office worker crosses paths with a soap maker.",
                genres = listOf(TmdbGenre(id = 18, name = "Drama")),
                posterPath = "/tmdb-poster.jpg",
                credits = TmdbCreditsResponse(cast = emptyList(), crew = emptyList()),
                images = TmdbImagesResponse(logos = emptyList(), backdrops = emptyList()),
                releaseDates = TmdbMovieReleaseDatesResponse(results = emptyList())
            )
        )

        val enrichment = service.fetchEnrichment(
            tmdbId = "550",
            contentType = ContentType.MOVIE,
            language = "en-US"
        )

        assertNotNull(enrichment)
        assertNull(enrichment?.poster)
    }

    @Test
    fun `imdbToTmdb joins duplicate concurrent lookups`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        val lookupStarted = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Int?>()
        coEvery {
            externalIdLookupProvider.findTmdbIdByImdbId("tt0137523", "movie")
        } coAnswers {
            lookupStarted.complete(Unit)
            response.await()
        }

        val service = TmdbService(externalIdLookupProvider)
        val first = async { service.imdbToTmdb("tt0137523", "movie") }
        val second = async { service.imdbToTmdb("tt0137523", "movie") }
        lookupStarted.await()
        yield()

        response.complete(550)

        assertEquals(550, first.await())
        assertEquals(550, second.await())
        coVerify(exactly = 1) {
            externalIdLookupProvider.findTmdbIdByImdbId("tt0137523", "movie")
        }
    }

    @Test
    fun `tmdbToImdb caches movie and tv ids separately when numeric id matches`() = runTest {
        val externalIdLookupProvider = mockk<DefaultTmdbExternalIdLookupProvider>()
        coEvery {
            externalIdLookupProvider.findImdbIdByTmdbId(1, "movie")
        } returns "tt0000001"
        coEvery {
            externalIdLookupProvider.findImdbIdByTmdbId(1, "tv")
        } returns "tt9999999"

        val service = TmdbService(externalIdLookupProvider)

        assertEquals("tt0000001", service.tmdbToImdb(1, "movie"))
        assertEquals("tt9999999", service.tmdbToImdb(1, "series"))

        coVerify(exactly = 1) { externalIdLookupProvider.findImdbIdByTmdbId(1, "movie") }
        coVerify(exactly = 1) { externalIdLookupProvider.findImdbIdByTmdbId(1, "tv") }
    }

    @Test
    fun `fetchEpisodeEnrichment reuses season level cache across overlapping requests`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val service = buildMetadataService(tmdbApi)

        coEvery {
            tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US")
        } returns Response.success(TmdbSeasonResponse(episodes = listOf(tmdbEpisode(1, "Pilot"))))
        coEvery {
            tmdbApi.getTvSeasonDetails(100, 2, "tmdb-key", "en-US")
        } returns Response.success(TmdbSeasonResponse(episodes = listOf(tmdbEpisode(1, "Second season"))))
        coEvery {
            tmdbApi.getTvSeasonDetails(100, 3, "tmdb-key", "en-US")
        } returns Response.success(TmdbSeasonResponse(episodes = listOf(tmdbEpisode(1, "Third season"))))

        val initial = service.fetchEpisodeEnrichment("100", listOf(1, 2), "en-US")
        val overlapping = service.fetchEpisodeEnrichment("100", listOf(2, 3), "en-US")

        assertEquals("Second season", initial[2 to 1]?.title)
        assertEquals("Second season", overlapping[2 to 1]?.title)
        assertEquals("Third season", overlapping[3 to 1]?.title)
        coVerify(exactly = 1) { tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US") }
        coVerify(exactly = 1) { tmdbApi.getTvSeasonDetails(100, 2, "tmdb-key", "en-US") }
        coVerify(exactly = 1) { tmdbApi.getTvSeasonDetails(100, 3, "tmdb-key", "en-US") }
    }

    @Test
    fun `fetchEpisodeEnrichment cancels joined in-flight waiters when owner is cancelled`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val service = buildMetadataService(tmdbApi)
        val requestStarted = CompletableDeferred<Unit>()
        val releaseRequest = CompletableDeferred<Unit>()

        coEvery {
            tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US")
        } coAnswers {
            requestStarted.complete(Unit)
            releaseRequest.await()
            Response.success(TmdbSeasonResponse(episodes = listOf(tmdbEpisode(1, "Pilot"))))
        }

        val owner = async {
            service.fetchEpisodeEnrichment("100", listOf(1), "en-US")
        }
        requestStarted.await()

        val joinedWaiter = async {
            service.fetchEpisodeEnrichment("100", listOf(1), "en-US")
        }
        yield()

        owner.cancel()

        try {
            owner.await()
            fail("Expected owner cancellation")
        } catch (_: CancellationException) {
        }

        try {
            withTimeout(1_000) { joinedWaiter.await() }
            fail("Expected joined waiter cancellation")
        } catch (_: CancellationException) {
        }

        coVerify(exactly = 1) { tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US") }
    }

    private fun buildMetadataService(
        tmdbApi: TmdbApi,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null
    ): TmdbMetadataService {
        val context = mockk<Context>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>()

        every {
            metadataDiskCacheStore.readTmdbEnrichment(any(), any(), any())
        } returns null
        every {
            metadataDiskCacheStore.writeTmdbEnrichment(any(), any(), any(), any())
        } returns Unit
        coEvery { posterRatingsUrlResolver.getActiveProvider() } returns activePosterProvider
        every {
            posterRatingsUrlResolver.resolvePosterUrl(any(), any(), any(), null)
        } answers {
            firstArg()
        }
        if (activePosterProvider != null) {
            every {
                posterRatingsUrlResolver.resolvePosterUrl(any(), any(), any(), activePosterProvider)
            } returns "provider-poster"
        }

        val runtime = passThroughTestRuntime()
        val credentialProvider = suspend {
            com.nexio.tv.core.metadata.MetadataProviderCredential(
                apiKey = "tmdb-key",
                source = MetadataCredentialSource.CUSTOM
            )
        }
        return TmdbMetadataService(
            appContext = context,
            tmdbApi = tmdbApi,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbCredentialProvider = credentialProvider,
            metadataDiskCacheStore = metadataDiskCacheStore,
            integrationRuntime = runtime,
            ownershipFactory = IntegrationCacheOwnershipFactory(RailMediaIdentityResolver()),
            tmdbIntegrationProvider = TmdbIntegrationProvider(
                runtime = runtime,
                tmdbApi = tmdbApi,
                tmdbCredentialProvider = credentialProvider
            )
        )
    }

    private fun tmdbEpisode(episodeNumber: Int, name: String): TmdbEpisode = TmdbEpisode(
        id = episodeNumber,
        episodeNumber = episodeNumber,
        name = name,
        overview = "Overview $episodeNumber",
        stillPath = "/still-$episodeNumber.jpg",
        airDate = "2020-01-0$episodeNumber",
        runtime = 45
    )
}
