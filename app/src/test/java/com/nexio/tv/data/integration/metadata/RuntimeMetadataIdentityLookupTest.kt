package com.nexio.tv.data.integration.metadata

import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResponse
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResult
import com.nexio.tv.data.remote.api.TvdbSeriesBaseRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMetadataIdentityLookupTest {
    @Test
    fun `tmdbMovieToImdb calls TMDB provider with movie media type`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findImdbIdByTmdbId(550, "movie") } returns "tt0137523"
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbMovieToImdb("550")

        assertEquals("tt0137523", result)
        coVerify(exactly = 1) { tmdbProvider.findImdbIdByTmdbId(550, "movie") }
    }

    @Test
    fun `tmdbTvToImdb calls TMDB provider with tv media type`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findImdbIdByTmdbId(1399, "tv") } returns "tt0944947"
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbTvToImdb("1399")

        assertEquals("tt0944947", result)
        coVerify(exactly = 1) { tmdbProvider.findImdbIdByTmdbId(1399, "tv") }
    }

    @Test
    fun `tmdbTvToTvdb calls TMDB provider tv external ids`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findTvdbIdByTmdbTvId(1399) } returns 121361
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbTvToTvdb("1399")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tmdbProvider.findTvdbIdByTmdbTvId(1399) }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tvdbProvider.searchByRemoteId(any()) }
    }

    @Test
    fun `tmdbToTvdb uses direct TMDB tv external tvdb id before imdb bridge`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findTvdbIdByTmdbTvId(1399) } returns 121361
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbToTvdb("1399")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tmdbProvider.findTvdbIdByTmdbTvId(1399) }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tvdbProvider.searchByRemoteId(any()) }
    }

    @Test
    fun `imdbToTmdbMovie calls TMDB provider with movie media type and stringifies result`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findTmdbIdByImdbId("tt0137523", "movie") } returns 550
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.imdbToTmdbMovie("tt0137523")

        assertEquals("550", result)
        coVerify(exactly = 1) { tmdbProvider.findTmdbIdByImdbId("tt0137523", "movie") }
    }

    @Test
    fun `imdbToTmdbMovie ignores blank IMDB id`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.imdbToTmdbMovie("   ")

        assertNull(result)
        coVerify(exactly = 0) { tmdbProvider.findTmdbIdByImdbId(any(), any()) }
    }

    @Test
    fun `tvdbSeriesToImdb reads IMDB remote id from TVDB extended series`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery {
            tvdbProvider.fetchSeriesExtended(tvdbId = 121361, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 121361,
            remoteIds = listOf(
                TvdbRemoteId(id = "12345", sourceName = "TMDB"),
                TvdbRemoteId(id = "tt0944947", sourceName = "IMDB")
            )
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tvdbSeriesToImdb("121361")

        assertEquals("tt0944947", result)
        coVerify(exactly = 1) {
            tvdbProvider.fetchSeriesExtended(tvdbId = 121361, meta = null, short = false)
        }
        coVerify(exactly = 0) { tmdbProvider.findTmdbIdByImdbId(any(), any()) }
    }

    @Test
    fun `tvdbSeriesToImdb normalizes TVDB IMDB source aliases`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery {
            tvdbProvider.fetchSeriesExtended(tvdbId = 463433, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 463433,
            remoteIds = listOf(
                TvdbRemoteId(id = "tt12345678", sourceName = "IMDb.com")
            )
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tvdbSeriesToImdb("463433")

        assertEquals("tt12345678", result)
    }

    @Test
    fun `tvdbToTmdb does not call TMDB when TVDB IMDB remote id is blank`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery {
            tvdbProvider.fetchSeriesExtended(tvdbId = 121361, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 121361,
            remoteIds = listOf(TvdbRemoteId(id = "   ", sourceName = "IMDB"))
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tvdbToTmdb("121361")

        assertNull(result)
        coVerify(exactly = 0) { tmdbProvider.findTmdbIdByImdbId(any(), any()) }
    }

    @Test
    fun `imdbToTvdbSeries returns first TVDB series id from remote id search`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery { tvdbProvider.searchByRemoteId("tt0944947") } returns TvdbRemoteIdSearchResponse(
            data = listOf(
                TvdbRemoteIdSearchResult(series = TvdbSeriesBaseRecord(id = 121361)),
                TvdbRemoteIdSearchResult(series = TvdbSeriesBaseRecord(id = 999999))
            )
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.imdbToTvdbSeries("tt0944947")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tvdbProvider.searchByRemoteId("tt0944947") }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbProvider.findTmdbIdByImdbId(any(), any()) }
    }

    @Test
    fun `imdbToTvdbSeries ignores blank IMDB id`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.imdbToTvdbSeries("   ")

        assertNull(result)
        coVerify(exactly = 0) { tvdbProvider.searchByRemoteId(any()) }
    }

    @Test
    fun `imdbToTvdb skips remote id results without series ids`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery { tvdbProvider.searchByRemoteId("tt0944947") } returns TvdbRemoteIdSearchResponse(
            data = listOf(
                TvdbRemoteIdSearchResult(series = null),
                TvdbRemoteIdSearchResult(series = TvdbSeriesBaseRecord(id = null)),
                TvdbRemoteIdSearchResult(series = TvdbSeriesBaseRecord(id = 121361))
            )
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.imdbToTvdb("tt0944947")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tvdbProvider.searchByRemoteId("tt0944947") }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbProvider.findTmdbIdByImdbId(any(), any()) }
    }
}
