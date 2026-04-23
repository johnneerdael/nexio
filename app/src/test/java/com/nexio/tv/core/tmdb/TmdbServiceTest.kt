package com.nexio.tv.core.tmdb

import com.nexio.tv.data.integration.tmdb.TmdbExternalIdLookupProvider
import com.nexio.tv.data.remote.api.TmdbExternalIdsResponse
import com.nexio.tv.data.remote.api.TmdbFindResponse
import com.nexio.tv.data.remote.api.TmdbFindResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TmdbServiceTest {

    @Test
    fun `imdbToTmdb resolves through external id lookup provider`() = runTest {
        val provider = mockk<TmdbExternalIdLookupProvider>()
        val response = CompletableDeferred<Response<TmdbFindResponse>>()
        coEvery { provider.findByExternalId("tt1111111", "tmdb-key") } coAnswers { response.await() }

        val service = TmdbService(provider) { tmdbCredential() }
        val result = async { service.imdbToTmdb("tt1111111", "movie") }

        response.complete(
            Response.success(
                TmdbFindResponse(
                    movieResults = listOf(TmdbFindResult(id = 99, title = "Example"))
                )
            )
        )

        assertEquals(99, result.await())
        coVerify(exactly = 1) { provider.findByExternalId("tt1111111", "tmdb-key") }
    }

    @Test
    fun `tmdbToImdb uses provider movie and tv adapters`() = runTest {
        val provider = mockk<TmdbExternalIdLookupProvider>()
        val service = TmdbService(provider) { tmdbCredential() }
        coEvery { provider.getMovieExternalIds(10, "tmdb-key") } returns Response.success(
            TmdbExternalIdsResponse(id = 10, imdbId = "tt0101010")
        )
        coEvery { provider.getTvExternalIds(11, "tmdb-key") } returns Response.success(
            TmdbExternalIdsResponse(id = 11, imdbId = "tt0202020")
        )


        assertEquals("tt0101010", service.tmdbToImdb(10, "movie"))
        assertEquals("tt0202020", service.tmdbToImdb(11, "series"))

        coVerify(exactly = 1) { provider.getMovieExternalIds(10, "tmdb-key") }
        coVerify(exactly = 1) { provider.getTvExternalIds(11, "tmdb-key") }
    }

    @Test
    fun `ensureTmdbId delegates to imdb lookup for imdb style ids`() = runTest {
        val provider = mockk<TmdbExternalIdLookupProvider>()
        coEvery { provider.findByExternalId("tt1234567", "tmdb-key") } returns Response.success(
            TmdbFindResponse(
                movieResults = listOf(TmdbFindResult(id = 7, title = "Imdb title"))
            )
        )

        val service = TmdbService(provider) { tmdbCredential() }

        assertEquals("7", service.ensureTmdbId("tt1234567", "movie"))
        coVerify(exactly = 1) { provider.findByExternalId("tt1234567", "tmdb-key") }
    }

    @Test
    fun `ensureTmdbId returns null for unsupported ids`() = runTest {
        val provider = mockk<TmdbExternalIdLookupProvider>()
        val service = TmdbService(provider) { tmdbCredential() }

        assertNull(service.ensureTmdbId("not-an-id", "movie"))
    }

    private fun tmdbCredential(): String =
        "tmdb-key"
}
