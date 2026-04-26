package com.nexio.tv.core.tvdb

import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
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
                TvdbTrailerRecord(url = "https://youtu.be/abcdefghijk")
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
}
