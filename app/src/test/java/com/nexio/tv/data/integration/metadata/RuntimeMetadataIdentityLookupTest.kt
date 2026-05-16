package com.nexio.tv.data.integration.metadata

import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResponse
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResult
import com.nexio.tv.data.remote.api.TvdbSearchResponse
import com.nexio.tv.data.remote.api.TvdbSearchResult
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
        coEvery { tvdbProvider.fetchSeriesExtended(121361, meta = null, short = false) } returns TvdbSeriesExtendedRecord(id = 121361)
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbTvToTvdb("1399")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tmdbProvider.findTvdbIdByTmdbTvId(1399) }
        coVerify(exactly = 1) { tvdbProvider.fetchSeriesExtended(121361, meta = null, short = false) }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tvdbProvider.searchByRemoteId(any()) }
    }

    @Test
    fun `tmdbToTvdb uses direct TMDB tv external tvdb id before imdb bridge`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>(relaxed = true)
        coEvery { tmdbProvider.findTvdbIdByTmdbTvId(1399) } returns 121361
        coEvery { tvdbProvider.fetchSeriesExtended(121361, meta = null, short = false) } returns TvdbSeriesExtendedRecord(id = 121361)
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbToTvdb("1399")

        assertEquals("121361", result)
        coVerify(exactly = 1) { tmdbProvider.findTvdbIdByTmdbTvId(1399) }
        coVerify(exactly = 1) { tvdbProvider.fetchSeriesExtended(121361, meta = null, short = false) }
        coVerify(exactly = 0) { tmdbProvider.findImdbIdByTmdbId(any(), any()) }
        coVerify(exactly = 0) { tvdbProvider.searchByRemoteId(any()) }
    }

    @Test
    fun `tmdbTvToTvdb falls back to TVDB exact alias when TMDB child tvdb id is not canonical`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery { tmdbProvider.findTvdbIdByTmdbTvId(308014) } returns 477676
        coEvery { tvdbProvider.fetchSeriesExtended(477676, meta = null, short = false) } returns null
        coEvery {
            tmdbProvider.fetchTvCore(308014, "en-US", null)
        } returns tmdbIdentityEnrichment("Berlin and the Lady with an Ermine")
        coEvery { tvdbProvider.searchSeriesByQuery("Berlin and the Lady with an Ermine") } returns TvdbSearchResponse(
            data = listOf(
                TvdbSearchResult(
                    tvdbId = "413033",
                    name = "Berlín",
                    aliases = listOf("Money Heist - Berlin", "Berlin and the Lady with an Ermine"),
                    remoteIds = listOf(TvdbRemoteId(id = "146176", sourceName = "TheMovieDB.com"))
                )
            )
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbTvToTvdb("308014")

        assertEquals("413033", result)
        coVerify(exactly = 1) { tmdbProvider.findTvdbIdByTmdbTvId(308014) }
        coVerify(exactly = 1) { tvdbProvider.fetchSeriesExtended(477676, meta = null, short = false) }
        coVerify(exactly = 1) { tmdbProvider.fetchTvCore(308014, "en-US", null) }
        coVerify(exactly = 1) { tvdbProvider.searchSeriesByQuery("Berlin and the Lady with an Ermine") }
    }

    @Test
    fun `tmdbTvToTvdb discards non canonical direct TVDB id when alias search misses`() = runTest {
        val tmdbProvider = mockk<TmdbIntegrationProvider>()
        val tvdbProvider = mockk<TvdbIntegrationProvider>()
        coEvery { tmdbProvider.findTvdbIdByTmdbTvId(308014) } returns 477676
        coEvery { tvdbProvider.fetchSeriesExtended(477676, meta = null, short = false) } returns null
        coEvery {
            tmdbProvider.fetchTvCore(308014, "en-US", null)
        } returns tmdbIdentityEnrichment("Berlin and the Lady with an Ermine")
        coEvery { tvdbProvider.searchSeriesByQuery("Berlin and the Lady with an Ermine") } returns TvdbSearchResponse(
            data = listOf(TvdbSearchResult(tvdbId = "999999", name = "Different Berlin"))
        )
        val lookup = RuntimeMetadataIdentityLookup(
            tmdbProvider = tmdbProvider,
            tvdbProvider = tvdbProvider
        )

        val result = lookup.tmdbTvToTvdb("308014")

        assertNull(result)
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

    private fun tmdbIdentityEnrichment(title: String): TmdbEnrichment =
        TmdbEnrichment(
            localizedTitle = title,
            description = null,
            genres = emptyList(),
            backdrop = null,
            logo = null,
            poster = null,
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = null,
            rating = null,
            runtimeMinutes = null,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = null,
            collectionId = null,
            collectionName = null
        )
}
