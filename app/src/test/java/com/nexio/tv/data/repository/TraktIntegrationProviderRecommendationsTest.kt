package com.nexio.tv.data.repository

import com.nexio.tv.core.integration.DefaultIntegrationRuntime
import com.nexio.tv.core.integration.InMemoryIntegrationProviderBackoffDao
import com.nexio.tv.core.integration.IntegrationBackoffManager
import com.nexio.tv.core.integration.IntegrationPlaybackGate
import com.nexio.tv.core.integration.IntegrationSingleFlight
import com.nexio.tv.core.integration.ProviderRequestGate
import com.nexio.tv.core.integration.RecordingIntegrationAuditSink
import com.nexio.tv.core.integration.RecordingIntegrationRuntime
import com.nexio.tv.core.integration.defaultIntegrationPolicyRegistry
import com.nexio.tv.core.integration.tempIntegrationBlobStore
import com.nexio.tv.data.local.integration.IntegrationCacheDao
import com.nexio.tv.data.local.integration.IntegrationCacheEntity
import com.nexio.tv.data.local.integration.LocalIntegrationCacheStore
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktRecommendationItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktTrendingShowItemDto
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class TraktIntegrationProviderRecommendationsTest {
    @Test
    fun `last activities executes through runtime call`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = TraktLastActivitiesResponseDto()
        )
        val traktApi = mockk<TraktApi>()
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = traktApi,
            traktAuthService = authServiceForProfile(0)
        )

        provider.getLastActivities()

        assertEquals(1, runtime.callSpecs.size)
        coVerify(exactly = 0) {
            traktApi.getLastActivities(any())
        }
    }

    @Test
    fun `fetchRecommendations uses runtime cache key for trakt recommendations`() = runTest {
        val runtime = RecordingIntegrationRuntime(
            successValue = listOf(
                TraktRecommendationItemDto(
                    movie = TraktMovieDto(
                        title = "Fight Club",
                        year = 1999,
                        ids = TraktIdsDto(imdb = "tt0137523")
                    )
                )
            )
        )
        val traktApi = mockk<TraktApi>()
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = traktApi,
            traktAuthService = authServiceForProfile(0)
        )

        val recommendations = provider.fetchRecommendations(
            type = "movies",
            limit = 20
        )

        assertEquals(listOf("profile:0:trakt:recommendations:movies:limit:20"), runtime.keys)
        assertEquals(1, recommendations?.size)
        assertNotNull(recommendations?.firstOrNull()?.movie)
        assertEquals("Fight Club", recommendations?.firstOrNull()?.movie?.title)
        coVerify(exactly = 0) {
            traktApi.getRecommendations(any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `trending and popular reads use runtime cache keys`() = runTest {
        val runtime = RecordingIntegrationRuntime<List<TraktTrendingMovieItemDto>>(
            successValue = listOf(
                TraktTrendingMovieItemDto(
                    movie = TraktMovieDto(
                        title = "The Dark Knight",
                        year = 2008,
                        ids = TraktIdsDto(imdb = "tt0468569")
                    )
                )
            )
        )
        val traktApi = mockk<TraktApi>()
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = traktApi,
            traktAuthService = authServiceForProfile(0)
        )

        provider.fetchTrendingMovies(limit = 20)
        provider.fetchTrendingShows(limit = 20)
        provider.fetchPopularMovies(limit = 20)
        provider.fetchPopularShows(limit = 20)

        assertEquals(
            listOf(
                "profile:0:trakt:trending:movies:limit:20",
                "profile:0:trakt:trending:shows:limit:20",
                "profile:0:trakt:popular:movies:limit:20",
                "profile:0:trakt:popular:shows:limit:20"
            ),
            runtime.keys
        )
        coVerify(exactly = 0) { traktApi.getTrendingMovies(any(), any()) }
        coVerify(exactly = 0) { traktApi.getTrendingShows(any(), any()) }
        coVerify(exactly = 0) { traktApi.getPopularMovies(any(), any()) }
        coVerify(exactly = 0) { traktApi.getPopularShows(any(), any()) }
    }

    @Test
    fun `profile scoped trakt runtime caches do not cross read between profiles`() = runTest {
        val registry = defaultIntegrationPolicyRegistry()
        val runtime = DefaultIntegrationRuntime(
            cacheStore = LocalIntegrationCacheStore(
                cacheDao = InMemoryIntegrationCacheDao(),
                blobStore = tempIntegrationBlobStore()
            ),
            requestGate = ProviderRequestGate(registry),
            backoffManager = IntegrationBackoffManager(InMemoryIntegrationProviderBackoffDao()),
            singleFlight = IntegrationSingleFlight(),
            playbackGate = IntegrationPlaybackGate(),
            registry = registry,
            auditSink = RecordingIntegrationAuditSink()
        )
        val authService = mockk<TraktAuthService>()
        val traktApi = mockk<TraktApi>()
        var profileId = 1
        every { authService.currentAuthSession() } answers {
            TrackingAuthSession(TrackingProvider.TRAKT, profileId)
        }
        every { authService.currentTraktProfileId() } answers { profileId }
        coEvery {
            authService.executeAuthorizedRequestWithinRuntimeCall<List<TraktRecommendationItemDto>>(any(), any())
        } coAnswers {
            secondArg<suspend (String) -> Response<List<TraktRecommendationItemDto>>>().invoke("Bearer token")
        }
        coEvery {
            traktApi.getRecommendations(any(), any(), any(), any(), any())
        } returnsMany listOf(
            Response.success(
                listOf(
                    TraktRecommendationItemDto(
                        movie = TraktMovieDto(
                            title = "Profile One",
                            year = 2001,
                            ids = TraktIdsDto(imdb = "tt0000001")
                        )
                    )
                )
            ),
            Response.success(
                listOf(
                    TraktRecommendationItemDto(
                        movie = TraktMovieDto(
                            title = "Profile Two",
                            year = 2002,
                            ids = TraktIdsDto(imdb = "tt0000002")
                        )
                    )
                )
            )
        )
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = traktApi,
            traktAuthService = authService
        )

        val profileOne = provider.fetchRecommendations(type = "movies", limit = 20)
        profileId = 2
        val profileTwo = provider.fetchRecommendations(type = "movies", limit = 20)

        assertEquals("Profile One", profileOne?.firstOrNull()?.movie?.title)
        assertEquals("Profile Two", profileTwo?.firstOrNull()?.movie?.title)
        coVerify(exactly = 2) {
            traktApi.getRecommendations(any(), "movies", 20, any(), any())
        }
    }

    @Test
    fun `profile scoped load uses the session captured with the cache key`() = runTest {
        val runtime = RecordingIntegrationRuntime<List<TraktRecommendationItemDto>>()
        val authService = mockk<TraktAuthService>()
        val traktApi = mockk<TraktApi>()
        var profileId = 1
        every { authService.currentAuthSession() } answers {
            TrackingAuthSession(TrackingProvider.TRAKT, profileId)
        }
        coEvery {
            authService.executeAuthorizedRequestWithinRuntimeCall<List<TraktRecommendationItemDto>>(any(), any())
        } returns Response.success(emptyList())
        val provider = TraktIntegrationProvider(
            runtime = runtime,
            traktApi = traktApi,
            traktAuthService = authService
        )

        provider.fetchRecommendations(type = "movies", limit = 20)
        profileId = 2
        runtime.specs.single().load()

        coVerify(exactly = 1) {
            authService.executeAuthorizedRequestWithinRuntimeCall(
                TrackingAuthSession(TrackingProvider.TRAKT, 1),
                any<suspend (String) -> Response<List<TraktRecommendationItemDto>>>()
            )
        }
    }
}

private fun authServiceForProfile(profileId: Int): TraktAuthService =
    mockk<TraktAuthService>(relaxed = true) {
        every { currentAuthSession() } returns TrackingAuthSession(TrackingProvider.TRAKT, profileId)
        every { currentTraktProfileId() } returns profileId
    }

private class InMemoryIntegrationCacheDao : IntegrationCacheDao {
    private val entries = linkedMapOf<String, IntegrationCacheEntity>()

    override suspend fun upsertCacheEntry(entity: IntegrationCacheEntity) {
        entries[entity.cacheKey] = entity
    }

    override suspend fun getCacheEntry(cacheKey: String): IntegrationCacheEntity? =
        entries[cacheKey]

    override suspend fun findByMediaKey(mediaKey: String): List<IntegrationCacheEntity> =
        entries.values.filter { it.ownerToken == mediaKey }

    override suspend fun deleteByMediaKey(mediaKey: String): Int {
        val keys = entries.values.filter { it.ownerToken == mediaKey }.map { it.cacheKey }
        keys.forEach(entries::remove)
        return keys.size
    }
}
