package com.nexio.tv.core.tvdb

import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbSeasonBaseRecord
import com.nexio.tv.data.remote.api.TvdbSeasonExtendedRecord
import com.nexio.tv.data.remote.api.TvdbSeasonTypeRecord
import com.nexio.tv.data.remote.api.TvdbTrailerRecord
import com.nexio.tv.domain.model.TvdbSettings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvdbTrailerResolverTest {
    @Test
    fun `trailer resolver fetches series record through tvdb integration provider`() = runTest {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>()
        coEvery {
            identityService.resolveSeriesByTvdbId(100)
        } returns TvdbSeriesIdentity(tvdbId = 100)
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 100,
            trailers = listOf(
                TvdbTrailerRecord(url = "https://youtu.be/abcdefghijk", language = "eng")
            )
        )
        val resolver = TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals("abcdefghijk", (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId)
        coVerify(exactly = 1) {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        }
    }

    @Test
    fun `title trailer defaults to latest season record trailer when tvdb lists multiple seasons`() = runTest {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>()
        coEvery {
            identityService.resolveSeriesByTvdbId(100)
        } returns TvdbSeriesIdentity(tvdbId = 100)
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 100,
            trailers = listOf(
                TvdbTrailerRecord(name = "Series Trailer", url = "https://youtu.be/aaaaaaaaaaa", language = "eng")
            ),
            seasons = listOf(
                tvdbSeason(id = 101, number = 1),
                tvdbSeason(id = 102, number = 2),
                tvdbSeason(id = 103, number = 3)
            )
        )
        coEvery {
            provider.fetchSeasonExtended(103)
        } returns TvdbSeasonExtendedRecord(
            id = 103,
            number = 3,
            trailers = listOf(
                TvdbTrailerRecord(name = "Trailer", url = "https://youtu.be/ccccccccccc", language = "eng")
            )
        )
        coEvery { provider.fetchSeasonExtended(102) } returns null
        coEvery { provider.fetchSeasonExtended(101) } returns null
        val resolver = TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "tvdb:100",
            type = "tv",
            title = "Example",
            year = "2026"
        )

        assertTrue(result is TvdbTrailerLookupResult.ResolvedYouTube)
        assertEquals("ccccccccccc", (result as TvdbTrailerLookupResult.ResolvedYouTube).videoId)
        coVerify(exactly = 1) { provider.fetchSeasonExtended(103) }
        coVerify(exactly = 0) { provider.fetchSeasonExtended(102) }
        coVerify(exactly = 0) { provider.fetchSeasonExtended(101) }
    }

    @Test
    fun `trailer resolver does not send explicit anime ids to tvdb remote lookup`() = runTest {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>(relaxed = true)
        val provider = mockk<TvdbIntegrationProvider>(relaxed = true)
        val resolver = TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )

        val result = resolver.resolveTitleTrailer(
            contentId = "kitsu:48649",
            type = "tv",
            title = "Anime",
            year = "2026"
        )

        assertEquals(TvdbTrailerLookupResult.Missing, result)
        coVerify(exactly = 0) { identityService.resolveSeriesByRemoteId(any(), any()) }
        coVerify(exactly = 0) { provider.fetchSeriesExtended(any(), any(), any()) }
    }

    @Test
    fun `season trailer is not claimed when tvdb trailer lacks season metadata`() = runTest {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>()
        coEvery {
            identityService.resolveSeriesByTvdbId(100)
        } returns TvdbSeriesIdentity(tvdbId = 100)
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 100,
            trailers = listOf(
                TvdbTrailerRecord(name = "Season 5 Trailer", url = "https://youtu.be/abcdefghijk", language = "eng")
            )
        )
        val resolver = TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )

        val result = resolver.resolveSeasonTrailer(
            contentId = "tvdb:100",
            type = "tv",
            seasonNumber = 5,
            title = "Example",
            year = "2026"
        )

        assertEquals(TvdbTrailerLookupResult.Missing, result)
    }

    @Test
    fun `season recap is not claimed when tvdb trailer lacks season metadata`() = runTest {
        val settingsDataStore = mockk<TvdbSettingsDataStore>()
        every { settingsDataStore.settings } returns MutableStateFlow(TvdbSettings(enabled = true))
        val identityService = mockk<TvdbIdentityService>()
        coEvery {
            identityService.resolveSeriesByTvdbId(100)
        } returns TvdbSeriesIdentity(tvdbId = 100)
        val provider = mockk<TvdbIntegrationProvider>()
        coEvery {
            provider.fetchSeriesExtended(tvdbId = 100, meta = null, short = false)
        } returns TvdbSeriesExtendedRecord(
            id = 100,
            trailers = listOf(
                TvdbTrailerRecord(name = "Season 5 Recap", url = "https://youtu.be/abcdefghijk", language = "eng")
            )
        )
        val resolver = TvdbTrailerResolver(
            tvdbSettingsDataStore = settingsDataStore,
            tvdbIdentityService = identityService,
            tvdbIntegrationProvider = provider,
            tvdbTrailerMapper = TvdbTrailerMapper()
        )

        val result = resolver.resolveSeasonRecap(
            contentId = "tvdb:100",
            type = "tv",
            seasonNumber = 5,
            title = "Example",
            year = "2026"
        )

        assertEquals(TvdbTrailerLookupResult.Missing, result)
    }

    private fun tvdbSeason(id: Int, number: Int) = TvdbSeasonBaseRecord(
        id = id,
        number = number,
        type = TvdbSeasonTypeRecord(name = "Aired Order")
    )
}
