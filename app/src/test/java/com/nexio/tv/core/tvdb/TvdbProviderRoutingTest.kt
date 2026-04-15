package com.nexio.tv.core.tvdb

import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.TvdbValidationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 0 validation scaffold for META-05: skipped TMDB TV calls when TVDB succeeds.
 *
 * This test verifies that when TVDB advanced metadata succeeds for a TV series,
 * the provider router does not make duplicate TMDB TV surface calls. The diagnostic
 * event TMDB_TV_SKIPPED must be emitted.
 *
 * Expected to fail until Plan 09-01/09-02 wires the advanced metadata path.
 */
class TvdbProviderRoutingTest {

    @Test
    fun `advanced tvdb success records skipped tmdb tv call`() = runTest {
        // Given: TVDB is active and returns a successful enrichment.
        val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()

        every { tvdbSettingsDataStore.settings } returns flowOf(
            TvdbSettings(
                enabled = true,
                apiKey = "fake-key",
                validationStatus = TvdbValidationStatus.VALID
            )
        )
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val identity = TvdbSeriesIdentity(
            tvdbId = 73255,
            name = "Fringe",
            remoteIds = mapOf(TvdbRemoteIdSource.IMDB to setOf("tt1119644"))
        )
        coEvery {
            tvdbIdentityService.resolveSeriesByRemoteId(any(), any())
        } returns identity

        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 73255,
            localizedTitle = "Fringe",
            description = "A science-fiction series.",
            genres = listOf("Science Fiction", "Drama"),
            ageRating = "TV-14"
        )
        coEvery {
            tvdbMetadataService.fetchSeriesEnrichment(identity, any())
        } returns enrichment

        val router = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        // When: The router fetches enrichment for a TV series.
        val request = TvMetadataRequest(
            contentId = "tt1119644",
            contentType = ContentType.SERIES
        )
        val decision = router.fetchEnrichment(request)

        // Then: The result is from TVDB with TVDB_SUCCESS reason.
        assertEquals(TvProvider.TVDB, decision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_SUCCESS, decision.reason)
        assertNotNull(decision.value)

        // And: TMDB TV calls were not made (skipped).
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }

        // And: The diagnostics include TMDB_TV_SKIPPED.
        val skippedDiagnostic = decision.diagnostics.find {
            it.reason == TvMetadataDecisionReason.TMDB_TV_SKIPPED
        }
        assertNotNull(
            "Diagnostics must include TMDB_TV_SKIPPED when TVDB succeeds",
            skippedDiagnostic
        )
        assertTrue(
            "TMDB_TV_SKIPPED diagnostic must reference TVDB as the chosen provider",
            skippedDiagnostic?.provider == TvProvider.TVDB
        )
    }
}
