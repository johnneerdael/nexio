package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbRemoteId as ApiTvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResponse
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResult
import com.nexio.tv.data.remote.api.TvdbSeriesBaseRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedResponse
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
        val response = CompletableDeferred<Response<TvdbRemoteIdSearchResponse>>()
        val service = TvdbIdentityService(tvdbApi = tvdbApi, authService = authService)

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947")
        } coAnswers {
            response.await()
        }
        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, any(), any())
        } returns Response.success(TvdbSeriesExtendedResponse(data = gameOfThronesExtendedRecord()))

        val first = async {
            service.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        }
        val second = async {
            service.resolveSeriesByRemoteId("tt0944947", TvdbRemoteIdSource.IMDB)
        }

        response.complete(Response.success(gameOfThronesRemoteSearchResponse()))

        assertEquals(121361, first.await()?.tvdbId)
        assertEquals(121361, second.await()?.tvdbId)
        coVerify(exactly = 1) { tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947") }
        coVerify(exactly = 1) { tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, any(), any()) }
    }

    @Test
    fun `identity lookup preserves normalized TVDB IMDB TMDB TV_MAZE WIKIDATA OFFICIAL_SITE and OTHER IDs`() = runTest {
        val tvdbApi = mockk<TvdbApi>()
        val authService = mockk<TvdbAuthService>()
        val service = TvdbIdentityService(tvdbApi = tvdbApi, authService = authService)

        coEvery { authService.bearerToken() } returns "Bearer tvdb-token"
        coEvery {
            tvdbApi.searchByRemoteId("Bearer tvdb-token", "tt0944947")
        } returns Response.success(gameOfThronesRemoteSearchResponse())
        coEvery {
            tvdbApi.getSeriesExtended("Bearer tvdb-token", 121361, any(), any())
        } returns Response.success(TvdbSeriesExtendedResponse(data = gameOfThronesExtendedRecord()))

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

    private fun gameOfThronesRemoteSearchResponse(): TvdbRemoteIdSearchResponse = TvdbRemoteIdSearchResponse(
        data = listOf(
            TvdbRemoteIdSearchResult(
                series = TvdbSeriesBaseRecord(
                    id = 121361,
                    name = "Game of Thrones",
                    firstAired = "2011-04-17"
                )
            )
        )
    )

    private fun gameOfThronesExtendedRecord(): TvdbSeriesExtendedRecord = TvdbSeriesExtendedRecord(
        id = 121361,
        name = "Game of Thrones",
        firstAired = "2011-04-17",
        remoteIds = listOf(
            ApiTvdbRemoteId(id = "tt0944947", sourceName = "IMDb"),
            ApiTvdbRemoteId(id = "1399", sourceName = "TheMovieDB.com"),
            ApiTvdbRemoteId(id = "tvmaze:82", sourceName = "TVMaze"),
            ApiTvdbRemoteId(id = "Q23572", sourceName = "WikiData"),
            ApiTvdbRemoteId(id = "https://www.hbo.com/game-of-thrones", sourceName = "official site"),
            ApiTvdbRemoteId(id = "legacy:got", sourceName = "Legacy")
        )
    )
}
