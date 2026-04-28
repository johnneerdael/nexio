package com.nexio.tv.core.tmdb

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationLoadResult
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbEpisode
import com.nexio.tv.data.remote.api.TmdbCollectionPart
import com.nexio.tv.data.remote.api.TmdbCollectionResponse
import com.nexio.tv.data.remote.api.TmdbRecommendationResult
import com.nexio.tv.data.remote.api.TmdbRecommendationsResponse
import com.nexio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nexio.tv.data.remote.api.TmdbPersonResponse
import com.nexio.tv.data.remote.api.TmdbPersonSearchResponse
import com.nexio.tv.data.remote.api.TmdbCompanySearchResponse
import com.nexio.tv.data.remote.api.TmdbCompanySearchResult
import com.nexio.tv.data.remote.api.TmdbPersonSearchResult
import com.nexio.tv.data.remote.api.TmdbReviewAuthorDetails
import com.nexio.tv.data.remote.api.TmdbReviewResult
import com.nexio.tv.data.remote.api.TmdbReviewsResponse
import com.nexio.tv.data.remote.api.TmdbSeasonResponse
import com.nexio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.fail
import retrofit2.Response

class TmdbIntegrationProviderTest {

    @Test
    fun `loadTvSeasonEpisodes returns episode list from success response`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvSeasonDetails(
                tvId = 100,
                seasonNumber = 1,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbSeasonResponse(
                episodes = listOf(
                    TmdbEpisode(
                        id = 1,
                        episodeNumber = 1,
                        name = "Pilot",
                        overview = "Episode 1",
                        stillPath = "/still1.jpg",
                        airDate = "2026-01-01",
                        runtime = 44
                    ),
                    TmdbEpisode(
                        id = 2,
                        episodeNumber = 2,
                        name = "Episode 2",
                        overview = "Episode 2",
                        stillPath = "/still2.jpg",
                        airDate = "2026-01-02",
                        runtime = 45
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadTvSeasonEpisodes(tvId = 100, seasonNumber = 1, apiKey = "tmdb-key", normalizedLanguage = "en-US")
        assertTrue(result is IntegrationLoadResult.Success)
        val episodes = (result as IntegrationLoadResult.Success).value
        assertEquals(2, episodes.size)
        assertEquals("Pilot", episodes[0].name)
        coVerify(exactly = 1) {
            tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadPersonDetails returns person response from success response`() = runTest {
        val runtime = passThroughTestRuntime()
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getPersonDetails(personId = 555, apiKey = "tmdb-key", language = null)
        } returns Response.success(
            TmdbPersonResponse(
                id = 555,
                name = "Jane Director",
                biography = "Sample bio",
                knownForDepartment = "Acting"
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadPersonDetails(personId = 555)
        val person = (result as IntegrationLoadResult.Success).value
        assertEquals(555, person.id)
        assertEquals("Jane Director", person.name)
        coVerify(exactly = 1) {
            tmdbApi.getPersonDetails(555, "tmdb-key", null)
        }
    }

    @Test
    fun `loadPersonCombinedCredits returns credits response from success response`() = runTest {
        val runtime = passThroughTestRuntime()
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getPersonCombinedCredits(personId = 555, apiKey = "tmdb-key", language = null)
        } returns Response.success(TmdbPersonCreditsResponse())

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadPersonCombinedCredits(personId = 555)
        assertTrue(result is IntegrationLoadResult.Success)
        coVerify(exactly = 1) {
            tmdbApi.getPersonCombinedCredits(555, "tmdb-key", null)
        }
    }

    @Test
    fun `searchPeople returns person search response and calls tmdb person search endpoint`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.searchPeople(apiKey = "tmdb-key", query = "Jane", includeAdult = false)
        } returns Response.success(
            TmdbPersonSearchResponse(
                results = listOf(
                    TmdbPersonSearchResult(
                        id = 555,
                        name = "Jane"
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.searchPeople(query = "Jane")
        assertTrue(result is IntegrationLoadResult.Success)
        val response = (result as IntegrationLoadResult.Success).value
        assertEquals(1, response.results?.size)
        assertEquals(555, response.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.searchPeople("tmdb-key", "Jane", 1, false)
        }
    }

    @Test
    fun `searchCompanies returns company search response and calls tmdb company search endpoint`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.searchCompanies(apiKey = "tmdb-key", query = "Studio", page = 1)
        } returns Response.success(
            TmdbCompanySearchResponse(
                results = listOf(
                    TmdbCompanySearchResult(
                        id = 777,
                        name = "Studio"
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.searchCompanies(query = "Studio")
        assertTrue(result is IntegrationLoadResult.Success)
        val response = (result as IntegrationLoadResult.Success).value
        assertEquals(1, response.results?.size)
        assertEquals(777, response.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.searchCompanies("tmdb-key", "Studio", 1)
        }
    }

    @Test
    fun `loadMoreLikeThis returns movie recommendation response from movie endpoint`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getMovieRecommendations(
                movieId = 300,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbRecommendationsResponse(
                results = listOf(
                    TmdbRecommendationResult(
                        id = 1,
                        title = "Interstellar",
                        mediaType = "movie",
                        originalLanguage = "en",
                        posterPath = "/poster.jpg",
                        backdropPath = "/backdrop.jpg",
                        overview = "A movie recommendation",
                        releaseDate = "2014-11-07",
                        voteAverage = 8.6,
                        voteCount = 100
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadMoreLikeThis(
            tmdbId = 300,
            tmdbType = "movie",
            normalizedLanguage = "en-US"
        )
        val recommendations = (result as IntegrationLoadResult.Success).value
        assertEquals(1, recommendations.results?.size)
        assertEquals(1, recommendations.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.getMovieRecommendations(300, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadMoreLikeThis with max items sorts and truncates movie recommendations`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getMovieRecommendations(
                movieId = 300,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbRecommendationsResponse(
                results = listOf(
                    TmdbRecommendationResult(
                        id = 1,
                        title = "Low vote",
                        mediaType = "movie",
                        originalLanguage = "en",
                        posterPath = "/low.jpg",
                        backdropPath = "/low-bg.jpg",
                        overview = "Low vote recommendation",
                        releaseDate = "2020-01-01",
                        voteAverage = 5.0,
                        voteCount = 10
                    ),
                    TmdbRecommendationResult(
                        id = 2,
                        title = "High vote",
                        mediaType = "movie",
                        originalLanguage = "en",
                        posterPath = "/high.jpg",
                        backdropPath = "/high-bg.jpg",
                        overview = "High vote recommendation",
                        releaseDate = "2020-01-01",
                        voteAverage = 7.0,
                        voteCount = 200
                    ),
                    TmdbRecommendationResult(
                        id = 3,
                        title = "Fallback",
                        mediaType = "movie",
                        originalLanguage = "es",
                        posterPath = "/fallback.jpg",
                        backdropPath = "/fallback-bg.jpg",
                        overview = "Fallback language recommendation",
                        releaseDate = "2020-01-01",
                        voteAverage = 9.0,
                        voteCount = 300
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadMoreLikeThis(
            tmdbId = "300",
            contentType = ContentType.MOVIE,
            normalizedLanguage = "en-US",
            maxItems = 2
        )

        assertTrue(result is IntegrationLoadResult.Success)
        val recommendations = (result as IntegrationLoadResult.Success).value
        assertEquals(2, recommendations.size)
        assertEquals(listOf(2, 1), recommendations.map { it.id })
        coVerify(exactly = 1) {
            tmdbApi.getMovieRecommendations(300, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadReviews returns tv review results`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvReviews(
                tvId = 500,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbReviewsResponse(
                results = listOf(
                    TmdbReviewResult(
                        id = "review-1",
                        author = "reviewer",
                        content = "Insightful take",
                        authorDetails = TmdbReviewAuthorDetails(name = "Reviewer")
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadReviews(
            tmdbId = 500,
            tmdbType = "tv",
            normalizedLanguage = "en-US"
        )
        val reviews = (result as IntegrationLoadResult.Success).value
        assertEquals(1, reviews.results?.size)
        assertEquals("review-1", reviews.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.getTvReviews(500, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadReviews with max items maps review response`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getMovieReviews(
                movieId = 700,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbReviewsResponse(
                results = listOf(
                    TmdbReviewResult(
                        id = "review-new",
                        author = "new-reviewer",
                        content = "Latest review",
                        updatedAt = "2026-01-02",
                        authorDetails = TmdbReviewAuthorDetails(name = "New Reviewer", rating = 6.0)
                    ),
                    TmdbReviewResult(
                        id = "review-old",
                        author = "old-reviewer",
                        content = "Older review",
                        updatedAt = "2026-01-01",
                        authorDetails = TmdbReviewAuthorDetails(name = "Old Reviewer", rating = 10.0)
                    ),
                    TmdbReviewResult(
                        id = "review-three",
                        author = "other-reviewer",
                        content = "Third review",
                        createdAt = "2025-01-01",
                        authorDetails = TmdbReviewAuthorDetails(name = "Other Reviewer", rating = 8.0)
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadReviews(
            tmdbId = "700",
            contentType = ContentType.MOVIE,
            normalizedLanguage = "en-US",
            maxItems = 2
        )
        assertTrue(result is IntegrationLoadResult.Success)
        val reviews = (result as IntegrationLoadResult.Success).value
        assertEquals(2, reviews.size)
        assertEquals("review-new", reviews[0].id)
        assertEquals("review-old", reviews[1].id)
        coVerify(exactly = 1) {
            tmdbApi.getMovieReviews(700, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadMoreLikeThis routes tv content type to tv endpoint from string id`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvRecommendations(
                tvId = 400,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbRecommendationsResponse(
                results = listOf(
                    TmdbRecommendationResult(
                        id = 1,
                        title = "TV Rec",
                        mediaType = "tv",
                        originalLanguage = "en",
                        posterPath = "/poster.jpg",
                        backdropPath = "/backdrop.jpg",
                        overview = "A tv recommendation",
                        firstAirDate = "2010-01-01",
                        voteAverage = 8.0,
                        voteCount = 20
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadMoreLikeThis(
            tmdbId = "400",
            contentType = ContentType.SERIES,
            normalizedLanguage = "en-US"
        )

        assertTrue(result is IntegrationLoadResult.Success)
        val recommendations = (result as IntegrationLoadResult.Success).value
        assertEquals(1, recommendations.results?.size)
        assertEquals(1, recommendations.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.getTvRecommendations(400, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadReviews routes tv content type to tv endpoint from string id`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvReviews(
                tvId = 600,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbReviewsResponse(
                results = listOf(
                    TmdbReviewResult(
                        id = "review-1",
                        author = "reviewer",
                        content = "Great",
                        authorDetails = TmdbReviewAuthorDetails(name = "Reviewer")
                    )
                )
            )
        )

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadReviews(
            tmdbId = "600",
            contentType = ContentType.TV,
            normalizedLanguage = "en-US"
        )

        assertTrue(result is IntegrationLoadResult.Success)
        val reviews = (result as IntegrationLoadResult.Success).value
        assertEquals(1, reviews.results?.size)
        assertEquals("review-1", reviews.results?.first()?.id)
        coVerify(exactly = 1) {
            tmdbApi.getTvReviews(600, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadMoreLikeThis does not call api for non-numeric tmdb id`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadMoreLikeThis(
            tmdbId = "not-a-number",
            contentType = ContentType.MOVIE,
            normalizedLanguage = "en-US"
        )

        assertTrue(result is IntegrationLoadResult.HttpError)
        assertEquals(400, (result as IntegrationLoadResult.HttpError).statusCode)
        coVerify(exactly = 0) {
            tmdbApi.getMovieRecommendations(any(), any(), any())
        }
        coVerify(exactly = 0) {
            tmdbApi.getTvRecommendations(any(), any(), any())
        }
    }

    @Test
    fun `loadReviews does not call api for non-numeric tmdb id`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadReviews(
            tmdbId = "not-a-number",
            contentType = ContentType.MOVIE,
            normalizedLanguage = "en-US"
        )

        assertTrue(result is IntegrationLoadResult.HttpError)
        assertEquals(400, (result as IntegrationLoadResult.HttpError).statusCode)
        coVerify(exactly = 0) {
            tmdbApi.getMovieReviews(any(), any(), any())
        }
        coVerify(exactly = 0) {
            tmdbApi.getTvReviews(any(), any(), any())
        }
    }

    @Test
    fun `loadMovieCollection returns collection response`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getCollectionDetails(
                collectionId = 900,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.success(
            TmdbCollectionResponse(
                id = 900,
                name = "Collection Name",
                parts = listOf(
                    TmdbCollectionPart(
                        id = 901,
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

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val collection = (provider.loadMovieCollection(
            collectionId = 900,
            normalizedLanguage = "en-US"
        ) as IntegrationLoadResult.Success).value
        assertEquals(1, collection.parts?.size)
        assertEquals("Collection Name", collection.name)
        assertEquals(1, collection.parts?.size)
        coVerify(exactly = 1) {
            tmdbApi.getCollectionDetails(900, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadMovieCollection with missing credential does not call api`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = {
                MetadataProviderCredential(apiKey = "", source = MetadataCredentialSource.MISSING)
            }
        )

        val result = provider.loadMovieCollection(
            collectionId = 900,
            normalizedLanguage = "en-US"
        )

        assertTrue(result is IntegrationLoadResult.HttpError)
        assertEquals(401, (result as IntegrationLoadResult.HttpError).statusCode)
        coVerify(exactly = 0) { tmdbApi.getCollectionDetails(any(), any(), any()) }
    }

    @Test
    fun `loadRecommendationImages propagates cancellation`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvImages(any(), any(), any())
        } throws CancellationException("cancelled")

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        try {
            provider.loadRecommendationImages(
                tmdbId = 100,
                tmdbType = "tv",
                includeImageLanguage = "en,en-US,en,null"
            )
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
        coVerify(exactly = 1) {
            tmdbApi.getTvImages(100, "tmdb-key", "en,en-US,en,null")
        }
    }

    @Test
    fun `loadTvSeasonEpisodes maps failed season response to http error`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvSeasonDetails(
                tvId = 100,
                seasonNumber = 1,
                apiKey = "tmdb-key",
                language = "en-US"
            )
        } returns Response.error(404, "not found".toResponseBody("application/json".toMediaType()))

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        val result = provider.loadTvSeasonEpisodes(tvId = 100, seasonNumber = 1, apiKey = "tmdb-key", normalizedLanguage = "en-US")
        assertTrue(result is IntegrationLoadResult.HttpError)
        val httpError = result as IntegrationLoadResult.HttpError
        assertEquals(404, httpError.statusCode)
        coVerify(exactly = 1) {
            tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US")
        }
    }

    @Test
    fun `loadTvSeasonEpisodes propagates cancellation`() = runTest {
        val runtime = mockk<IntegrationRuntime>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        coEvery {
            tmdbApi.getTvSeasonDetails(any(), any(), any(), any())
        } throws CancellationException("cancelled")

        val provider = TmdbIntegrationProvider(
            runtime = runtime,
            tmdbApi = tmdbApi,
            tmdbCredentialProvider = { MetadataProviderCredential(apiKey = "tmdb-key", source = MetadataCredentialSource.CUSTOM) }
        )

        try {
            provider.loadTvSeasonEpisodes(tvId = 100, seasonNumber = 1, apiKey = "tmdb-key", normalizedLanguage = "en-US")
            fail("Expected CancellationException")
        } catch (exception: CancellationException) {
            assertEquals("cancelled", exception.message)
        }
        coVerify(exactly = 1) {
            tmdbApi.getTvSeasonDetails(100, 1, "tmdb-key", "en-US")
        }
    }
}
