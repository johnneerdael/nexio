package com.nexio.tv.core.tmdb

import android.content.Context
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbCreditsResponse
import com.nexio.tv.data.remote.api.TmdbDetailsResponse
import com.nexio.tv.data.remote.api.TmdbExternalIdsResponse
import com.nexio.tv.data.remote.api.TmdbFindResponse
import com.nexio.tv.data.remote.api.TmdbFindResult
import com.nexio.tv.data.remote.api.TmdbGenre
import com.nexio.tv.data.remote.api.TmdbImagesResponse
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDateCountry
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDateItem
import com.nexio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
                appendToResponse = "credits,images,release_dates",
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
                appendToResponse = "credits,images,release_dates",
                includeImageLanguage = "en,en-US,en,null"
            )
        }
        coVerify(exactly = 0) { tmdbApi.getMovieCredits(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieImages(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbApi.getMovieReleaseDates(any(), any()) }
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
                appendToResponse = "credits,images,release_dates",
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
        val tmdbApi = mockk<TmdbApi>()
        val settingsDataStore = mockk<TmdbSettingsDataStore>()
        val response = CompletableDeferred<Response<TmdbFindResponse>>()
        every { settingsDataStore.settings } returns flowOf(tmdbSettings())
        coEvery {
            tmdbApi.findByExternalId("tt0137523", "tmdb-key", "imdb_id")
        } coAnswers {
            response.await()
        }

        val service = TmdbService(tmdbApi, settingsDataStore)
        val first = async { service.imdbToTmdb("tt0137523", "movie") }
        val second = async { service.imdbToTmdb("tt0137523", "movie") }

        response.complete(
            Response.success(
                TmdbFindResponse(
                    movieResults = listOf(TmdbFindResult(id = 550, title = "Fight Club"))
                )
            )
        )

        assertEquals(550, first.await())
        assertEquals(550, second.await())
        coVerify(exactly = 1) {
            tmdbApi.findByExternalId("tt0137523", "tmdb-key", "imdb_id")
        }
    }

    @Test
    fun `tmdbToImdb caches movie and tv ids separately when numeric id matches`() = runTest {
        val tmdbApi = mockk<TmdbApi>()
        val settingsDataStore = mockk<TmdbSettingsDataStore>()
        every { settingsDataStore.settings } returns flowOf(tmdbSettings())
        coEvery {
            tmdbApi.getMovieExternalIds(1, "tmdb-key")
        } returns Response.success(TmdbExternalIdsResponse(id = 1, imdbId = "tt0000001"))
        coEvery {
            tmdbApi.getTvExternalIds(1, "tmdb-key")
        } returns Response.success(TmdbExternalIdsResponse(id = 1, imdbId = "tt9999999"))

        val service = TmdbService(tmdbApi, settingsDataStore)

        assertEquals("tt0000001", service.tmdbToImdb(1, "movie"))
        assertEquals("tt9999999", service.tmdbToImdb(1, "series"))

        coVerify(exactly = 1) { tmdbApi.getMovieExternalIds(1, "tmdb-key") }
        coVerify(exactly = 1) { tmdbApi.getTvExternalIds(1, "tmdb-key") }
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

    private fun buildMetadataService(
        tmdbApi: TmdbApi,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null
    ): TmdbMetadataService {
        val context = mockk<Context>(relaxed = true)
        val posterRatingsUrlResolver = mockk<PosterRatingsUrlResolver>()
        val settingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>()

        every { settingsDataStore.settings } returns flowOf(tmdbSettings())
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

        return TmdbMetadataService(
            appContext = context,
            tmdbApi = tmdbApi,
            posterRatingsUrlResolver = posterRatingsUrlResolver,
            tmdbSettingsDataStore = settingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore
        )
    }

    private fun tmdbSettings(): TmdbSettings = TmdbSettings(
        enabled = true,
        apiKey = "tmdb-key"
    )

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
