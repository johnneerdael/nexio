package com.nexio.tv.data.integration.metadata

import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.tvdb.TvdbIntegrationProvider
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResponse
import com.nexio.tv.data.remote.api.TvdbRemoteIdSearchResult
import com.nexio.tv.data.remote.api.TvdbSeriesBaseRecord
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class RuntimeMetadataIdentityLookupTest {
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
