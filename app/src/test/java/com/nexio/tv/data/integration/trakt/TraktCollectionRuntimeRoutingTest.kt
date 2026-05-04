package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktCollectionShowItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.domain.model.TrackingProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class TraktCollectionRuntimeRoutingTest {

    @Test
    fun getCollectionMovies_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
        val item = TraktCollectionMovieItemDto(
            collectedAt = "2014-09-01T09:10:11.000Z",
            movie = TraktMovieDto(
                title = "Batman Begins", year = 2005,
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getCollectionMovies(any(), any()) } returns Response.success(listOf(item))
        }
        val provider = buildMovieProvider(traktApi = traktApi, runtimeFixture = fixture)

        val first = unwrap(provider.getCollectionMovies())
        val second = unwrap(provider.getCollectionMovies())

        assertEquals(1, first?.size)
        assertEquals("tt0372784", second?.first()?.movie?.ids?.imdb)
        coVerify(exactly = 1) { traktApi.getCollectionMovies(any(), any()) }
    }

    @Test
    fun getCollectionShows_payload_carries_seasons_and_episodes() = runBlocking {
        val item = TraktCollectionShowItemDto(
            lastCollectedAt = "2014-07-14T01:00:00.000Z",
            show = TraktShowDto(
                title = "Breaking Bad", year = 2008,
                ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189, imdb = "tt0903747", tmdb = 1396)
            ),
            seasons = listOf(TraktCollectionSeasonDto(number = 1, episodes = listOf(
                TraktCollectionEpisodeDto(number = 1, collectedAt = "2014-07-14T01:00:00.000Z"),
                TraktCollectionEpisodeDto(number = 2, collectedAt = "2014-07-14T01:00:00.000Z")
            )))
        )
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getCollectionShows(any(), any()) } returns Response.success(listOf(item))
        }
        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        val items = unwrap(provider.getCollectionShows()).orEmpty()
        val bb = items.first { it.show?.ids?.tvdb == 81189 }
        assertEquals(1, bb.seasons?.size)
        assertEquals(2, bb.seasons?.first()?.episodes?.size)
    }

    @Test
    fun invalidateCollectionSnapshot_movies_then_read_hits_wire_again() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getCollectionMovies(any(), any()) } returns Response.success(emptyList())
        }
        val provider = buildMovieProvider(traktApi = traktApi, runtimeFixture = fixture)

        provider.getCollectionMovies()
        provider.invalidateCollectionSnapshot(TraktCollectionKind.MOVIES)
        provider.getCollectionMovies()

        coVerify(exactly = 2) { traktApi.getCollectionMovies(any(), any()) }
    }

    @Test
    fun invalidateCollectionSnapshot_shows_then_read_hits_wire_again() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getCollectionShows(any(), any()) } returns Response.success(emptyList())
        }
        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        provider.getCollectionShows()
        provider.invalidateCollectionSnapshot(TraktCollectionKind.SHOWS)
        provider.getCollectionShows()

        coVerify(exactly = 2) { traktApi.getCollectionShows(any(), any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun <T> unwrap(result: IntegrationCallResult<T>): T? =
        if (result is IntegrationCallResult.Success) result.value else null

    private fun buildMovieProvider(
        traktApi: TraktApi,
        runtimeFixture: com.nexio.tv.core.integration.ByteArrayRuntimeFixture
    ): TraktIntegrationProvider {
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = 1,
            credentialHash = "hash-p1"
        )
        val traktAuthService = mockk<TraktAuthService> {
            coEvery { accountScopedSession() } returns session
            coEvery { accountScopedSession(any()) } returns session
            coEvery {
                executeAuthorizedRequestWithinRuntimeCall(any(), any<suspend (String) -> Response<List<TraktCollectionMovieItemDto>>>())
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<List<TraktCollectionMovieItemDto>>>()
                block("Bearer hash-p1")
            }
        }
        return TraktIntegrationProvider(
            runtime = runtimeFixture.runtime,
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            cacheStore = runtimeFixture.cacheStore
        )
    }

    private fun buildShowProvider(
        traktApi: TraktApi,
        runtimeFixture: com.nexio.tv.core.integration.ByteArrayRuntimeFixture
    ): TraktIntegrationProvider {
        val session = TrackingAuthSession(
            provider = TrackingProvider.TRAKT,
            profileId = 1,
            credentialHash = "hash-p1"
        )
        val traktAuthService = mockk<TraktAuthService> {
            coEvery { accountScopedSession() } returns session
            coEvery { accountScopedSession(any()) } returns session
            coEvery {
                executeAuthorizedRequestWithinRuntimeCall(any(), any<suspend (String) -> Response<List<TraktCollectionShowItemDto>>>())
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<List<TraktCollectionShowItemDto>>>()
                block("Bearer hash-p1")
            }
        }
        return TraktIntegrationProvider(
            runtime = runtimeFixture.runtime,
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            cacheStore = runtimeFixture.cacheStore
        )
    }
}
