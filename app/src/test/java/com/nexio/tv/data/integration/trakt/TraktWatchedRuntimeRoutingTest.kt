package com.nexio.tv.data.integration.trakt

import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
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
    // Helpers
    // -------------------------------------------------------------------------

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
            traktAuthService = traktAuthService
        )
    }
}
