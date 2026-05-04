package com.nexio.tv.data.integration.trakt

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.integration.byteArrayRuntimeFixture
import com.nexio.tv.data.remote.api.TraktApi
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

    private val gson = Gson()

    @Test
    fun getWatched_movies_second_call_within_ttl_does_not_hit_traktApi() = runBlocking {
        val fixture = byteArrayRuntimeFixture()
        val fixtureJson = readFixture("trakt/sync_watched_movies.json")
        val parsed = parseList<TraktWatchedMovieItemDto>(fixtureJson)

        val traktApi = mockk<TraktApi> {
            coEvery { getWatched(any(), eq("movies"), any()) } returns Response.success(parsed)
        }
        val provider = buildProvider(traktApi = traktApi, runtimeFixture = fixture)

        val first = provider.getWatched(type = "movies")
            .let { if (it is com.nexio.tv.core.integration.IntegrationCallResult.Success) it.value else null }
        val second = provider.getWatched(type = "movies")
            .let { if (it is com.nexio.tv.core.integration.IntegrationCallResult.Success) it.value else null }

        assertEquals(1, first?.size)
        assertEquals(1, second?.size)
        coVerify(exactly = 1) { traktApi.getWatched(any(), eq("movies"), any()) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun readFixture(path: String): String =
        checkNotNull(
            TraktWatchedRuntimeRoutingTest::class.java.classLoader
                ?.getResourceAsStream("fixtures/$path")
        ) { "Fixture not found: fixtures/$path" }
            .bufferedReader()
            .readText()

    private inline fun <reified T> parseList(json: String): List<T> {
        val type = object : TypeToken<List<T>>() {}.type
        return gson.fromJson(json, type)
    }

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
