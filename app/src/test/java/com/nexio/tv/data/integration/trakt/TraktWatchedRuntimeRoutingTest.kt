package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedSeasonDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedShowItemDto
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

class TraktWatchedRuntimeRoutingTest {

    @Test
    fun getWatched_movies_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
        val fixture = byteArrayRuntimeFixture()

        val item = TraktWatchedMovieItemDto(
            plays = 4,
            lastWatchedAt = "2014-10-11T17:00:54.000Z",
            lastUpdatedAt = "2014-10-11T17:00:54.000Z",
            movie = TraktMovieDto(
                title = "Batman Begins",
                year = 2005,
                ids = TraktIdsDto(
                    trakt = 6,
                    slug = "batman-begins-2005",
                    imdb = "tt0372784",
                    tmdb = 272
                )
            )
        )

        val traktApi = mockk<TraktApi> {
            coEvery {
                getWatched(any(), eq("movies"), any())
            } returns Response.success(listOf(item))
        }
        val provider = buildProvider(traktApi = traktApi, runtimeFixture = fixture)

        val first = provider.getWatched(type = "movies")
            .let { if (it is com.nexio.tv.core.integration.IntegrationCallResult.Success) it.value else null }
        val second = provider.getWatched(type = "movies")
            .let { if (it is com.nexio.tv.core.integration.IntegrationCallResult.Success) it.value else null }

        assertEquals(1, first?.size)
        assertEquals(4, first?.get(0)?.plays)
        assertEquals("2014-10-11T17:00:54.000Z", first?.get(0)?.lastWatchedAt)
        assertEquals(272, first?.get(0)?.movie?.ids?.tmdb)

        assertEquals(1, second?.size)
        assertEquals(4, second?.get(0)?.plays)
        assertEquals("2014-10-11T17:00:54.000Z", second?.get(0)?.lastWatchedAt)
        assertEquals(272, second?.get(0)?.movie?.ids?.tmdb)

        coVerify(exactly = 1) { traktApi.getWatched(any(), eq("movies"), any()) }
    }

    // -------------------------------------------------------------------------
    // Shows tests
    // -------------------------------------------------------------------------

    @Test
    fun getWatchedShows_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getWatchedShows(any(), any()) } returns Response.success(listOf(breakingBadDto(), parksAndRecDto()))
        }

        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        val first = unwrap(provider.getWatchedShows())
        val second = unwrap(provider.getWatchedShows())

        assertEquals(2, first?.size)
        assertEquals(2, second?.size)
        coVerify(exactly = 1) { traktApi.getWatchedShows(any(), any()) }
    }

    @Test
    fun getWatchedShows_payload_carries_seasons_and_episodes() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getWatchedShows(any(), any()) } returns Response.success(listOf(breakingBadDto()))
        }

        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        val items = unwrap(provider.getWatchedShows()).orEmpty()
        val breakingBad = items.first { it.show?.ids?.tvdb == 81189 }
        assertEquals(2, breakingBad.seasons?.size)
        assertEquals(2, breakingBad.seasons?.first()?.episodes?.size)
        assertEquals("2014-10-11T17:00:54.000Z", breakingBad.seasons?.first()?.episodes?.first()?.lastWatchedAt)
    }

    @Test
    fun getWatchedShows_passes_null_extended_so_seasons_are_returned() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery { getWatchedShows(any(), any()) } returns Response.success(emptyList())
        }

        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        provider.getWatchedShows()

        coVerify { traktApi.getWatchedShows(any(), extended = null) }
    }

    // -------------------------------------------------------------------------
    // Invalidation tests
    // -------------------------------------------------------------------------

    @Test
    fun invalidateWatchedSnapshot_shows_then_read_hits_wire_again() = runBlocking {
        val items = listOf(breakingBadDto())
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery {
                getWatchedShows(any(), any())
            } returns Response.success(items)
        }
        val provider = buildShowProvider(traktApi = traktApi, runtimeFixture = fixture)

        provider.getWatchedShows()  // populates cache
        provider.invalidateWatchedSnapshot(TraktWatchedKind.SHOWS)
        provider.getWatchedShows()  // should re-fetch

        coVerify(exactly = 2) { traktApi.getWatchedShows(any(), any()) }
    }

    @Test
    fun invalidateWatchedSnapshot_movies_then_read_hits_wire_again() = runBlocking {
        val item = TraktWatchedMovieItemDto(
            plays = 4,
            lastWatchedAt = "2014-10-11T17:00:54.000Z",
            lastUpdatedAt = "2014-10-11T17:00:54.000Z",
            movie = TraktMovieDto(
                title = "Batman Begins",
                year = 2005,
                ids = TraktIdsDto(trakt = 6, slug = "batman-begins-2005", imdb = "tt0372784", tmdb = 272)
            )
        )
        val fixture = byteArrayRuntimeFixture()
        val traktApi = mockk<TraktApi> {
            coEvery {
                getWatched(any(), eq("movies"), any())
            } returns Response.success(listOf(item))
        }
        val provider = buildProvider(traktApi = traktApi, runtimeFixture = fixture)

        provider.getWatched(type = "movies")
        provider.invalidateWatchedSnapshot(TraktWatchedKind.MOVIES)
        provider.getWatched(type = "movies")

        coVerify(exactly = 2) { traktApi.getWatched(any(), eq("movies"), any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun <T> unwrap(result: IntegrationCallResult<T>): T? =
        if (result is IntegrationCallResult.Success) result.value else null

    private fun buildProvider(
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
                executeAuthorizedRequestWithinRuntimeCall(any(), any<suspend (String) -> Response<List<TraktWatchedMovieItemDto>>>())
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<List<TraktWatchedMovieItemDto>>>()
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
                executeAuthorizedRequestWithinRuntimeCall(any(), any<suspend (String) -> Response<List<TraktWatchedShowItemDto>>>())
            } coAnswers {
                val block = secondArg<suspend (String) -> Response<List<TraktWatchedShowItemDto>>>()
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

    private fun breakingBadDto() = TraktWatchedShowItemDto(
        plays = 56,
        lastWatchedAt = "2014-10-11T17:00:54.000Z",
        lastUpdatedAt = "2014-10-11T17:00:54.000Z",
        resetAt = null,
        show = TraktShowDto(
            title = "Breaking Bad",
            year = 2008,
            ids = TraktIdsDto(trakt = 1, slug = "breaking-bad", tvdb = 81189, imdb = "tt0903747", tmdb = 1396)
        ),
        seasons = listOf(
            TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2014-10-11T17:00:54.000Z"),
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2014-10-11T17:00:54.000Z")
            )),
            TraktWatchedSeasonDto(number = 2, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2014-10-11T17:00:54.000Z"),
                TraktWatchedEpisodeDto(number = 2, plays = 1, lastWatchedAt = "2014-10-11T17:00:54.000Z")
            ))
        )
    )

    private fun parksAndRecDto() = TraktWatchedShowItemDto(
        plays = 23,
        lastWatchedAt = "2019-08-13T17:00:54.000Z",
        lastUpdatedAt = "2019-08-13T17:00:54.000Z",
        resetAt = "2019-08-12T17:00:54.000Z",
        show = TraktShowDto(
            title = "Parks and Recreation",
            year = 2009,
            ids = TraktIdsDto(trakt = 4, slug = "parks-and-recreation", tvdb = 84912, imdb = "tt1266020", tmdb = 8592)
        ),
        seasons = listOf(
            TraktWatchedSeasonDto(number = 1, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2014-10-11T17:00:54.000Z")
            )),
            TraktWatchedSeasonDto(number = 2, episodes = listOf(
                TraktWatchedEpisodeDto(number = 1, plays = 1, lastWatchedAt = "2019-08-13T17:00:54.000Z")
            ))
        )
    )
}
