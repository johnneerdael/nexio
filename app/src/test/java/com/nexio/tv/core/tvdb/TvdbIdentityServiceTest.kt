package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.Response

class TvdbIdentityServiceTest {

    @Test
    fun `concurrent remote ID lookups join one TVDB search request`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val response = CompletableDeferred<Response<TvdbSeriesIdentity>>()
        val service = TvdbIdentityService(tvdbApi = tvdbApi, authService = authService)

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947")
        } coAnswers {
            response.await()
        }

        val first = async {
            service.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        }
        val second = async {
            service.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        }

        response.complete(Response.success(gameOfThronesIdentity()))

        assertEquals(121361, first.await()?.tvdbId)
        assertEquals(121361, second.await()?.tvdbId)
        coVerify(exactly = 1) { tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947") }
    }

    @Test
    fun `identity lookup preserves normalized TVDB IMDB TMDB TV_MAZE WIKIDATA OFFICIAL_SITE and OTHER IDs`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val service = TvdbIdentityService(tvdbApi = tvdbApi, authService = authService)

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947")
        } returns Response.success(gameOfThronesIdentity())

        val identity = service.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)

        assertNotNull(identity)
        assertEquals(setOf("121361"), identity?.remoteIds?.get(TvdbRemoteIdSource.TVDB))
        assertEquals(setOf("tt0944947"), identity?.remoteIds?.get(TvdbRemoteIdSource.IMDB))
        assertEquals(setOf("1399"), identity?.remoteIds?.get(TvdbRemoteIdSource.TMDB))
        assertEquals(setOf("tvmaze:82"), identity?.remoteIds?.get(TvdbRemoteIdSource.TV_MAZE))
        assertEquals(setOf("Q23572"), identity?.remoteIds?.get(TvdbRemoteIdSource.WIKIDATA))
        assertEquals(
            setOf("https://www.hbo.com/game-of-thrones"),
            identity?.remoteIds?.get(TvdbRemoteIdSource.OFFICIAL_SITE)
        )
        assertEquals(setOf("legacy:got"), identity?.remoteIds?.get(TvdbRemoteIdSource.OTHER))
    }

    private fun gameOfThronesIdentity(): TvdbSeriesIdentity = TvdbSeriesIdentity(
        tvdbId = 121361,
        name = "Game of Thrones",
        remoteIds = mapOf(
            TvdbRemoteIdSource.TVDB to setOf("121361"),
            TvdbRemoteIdSource.IMDB to setOf("tt0944947"),
            TvdbRemoteIdSource.TMDB to setOf("1399"),
            TvdbRemoteIdSource.TV_MAZE to setOf("tvmaze:82"),
            TvdbRemoteIdSource.WIKIDATA to setOf("Q23572"),
            TvdbRemoteIdSource.OFFICIAL_SITE to setOf("https://www.hbo.com/game-of-thrones"),
            TvdbRemoteIdSource.OTHER to setOf("legacy:got")
        )
    )
}
