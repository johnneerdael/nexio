package com.nexio.tv.core.tvdb

import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaCompanyKind
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
 * Tests for META-05: provider routing with TVDB advanced metadata.
 *
 * Verifies that when TVDB advanced metadata succeeds for a TV series,
 * the provider router does not make duplicate TMDB TV surface calls and
 * emits correct diagnostics (TMDB_TV_SKIPPED, advanced surface success/missing).
 */
class TvdbProviderRoutingTest {

    @Test
    fun `advanced tvdb success records skipped tmdb tv call`() = runTest {
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
            ageRating = "TV-14",
            castMembers = listOf(
                MetaCastMember(name = "Anna Torv", character = "Olivia Dunham")
            ),
            productionCompanies = listOf(
                MetaCompany(name = "Bad Robot", kind = MetaCompanyKind.COMPANY)
            ),
            networks = listOf(
                MetaCompany(name = "FOX", kind = MetaCompanyKind.NETWORK)
            )
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

        val request = TvMetadataRequest(
            contentId = "tt1119644",
            contentType = ContentType.SERIES
        )
        val decision = router.fetchEnrichment(request)

        // TVDB provider with success reason
        assertEquals(TvProvider.TVDB, decision.provider)
        assertEquals(TvMetadataDecisionReason.TVDB_SUCCESS, decision.reason)
        assertNotNull(decision.value)

        // TMDB TV calls were not made (skipped)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }

        // Diagnostics include TMDB_TV_SKIPPED
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

    @Test
    fun `advanced tvdb success with surfaces emits advanced surface success diagnostic`() = runTest {
        val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()

        every { tvdbSettingsDataStore.settings } returns flowOf(
            TvdbSettings(enabled = true, apiKey = "fake-key", validationStatus = TvdbValidationStatus.VALID)
        )
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val identity = TvdbSeriesIdentity(tvdbId = 73255, name = "Fringe")
        coEvery { tvdbIdentityService.resolveSeriesByRemoteId(any(), any()) } returns identity

        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 73255,
            localizedTitle = "Fringe",
            genres = listOf("Drama"),
            castMembers = listOf(MetaCastMember(name = "Anna Torv", character = "Olivia"))
        )
        coEvery { tvdbMetadataService.fetchSeriesEnrichment(identity, any()) } returns enrichment

        val router = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(contentId = "tt1119644", contentType = ContentType.SERIES)
        )

        val advancedSuccess = decision.diagnostics.find {
            it.reason == TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_SUCCESS
        }
        assertNotNull(
            "Diagnostics must include TVDB_ADVANCED_SURFACE_SUCCESS when advanced surfaces are present",
            advancedSuccess
        )
    }

    @Test
    fun `advanced tvdb success without surfaces emits advanced surface missing diagnostic`() = runTest {
        val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()

        every { tvdbSettingsDataStore.settings } returns flowOf(
            TvdbSettings(enabled = true, apiKey = "fake-key", validationStatus = TvdbValidationStatus.VALID)
        )
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val identity = TvdbSeriesIdentity(tvdbId = 99999, name = "Empty Show")
        coEvery { tvdbIdentityService.resolveSeriesByRemoteId(any(), any()) } returns identity

        // Enrichment with no advanced surfaces
        val enrichment = TvMetadataEnrichment(
            seriesTvdbId = 99999,
            localizedTitle = "Empty Show"
        )
        coEvery { tvdbMetadataService.fetchSeriesEnrichment(identity, any()) } returns enrichment

        val router = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(contentId = "tt0000000", contentType = ContentType.SERIES)
        )

        val advancedMissing = decision.diagnostics.find {
            it.reason == TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_MISSING
        }
        assertNotNull(
            "Diagnostics must include TVDB_ADVANCED_SURFACE_MISSING when all advanced surfaces are empty",
            advancedMissing
        )

        // TMDB still skipped even when advanced surfaces are empty (TVDB success path)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `tvdb identity missing emits tvdb_fallback_tmdb diagnostic`() = runTest {
        val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val tvdbIdentityService = mockk<TvdbIdentityService>()
        val tvdbMetadataService = mockk<TvdbMetadataService>()
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()

        every { tvdbSettingsDataStore.settings } returns flowOf(
            TvdbSettings(enabled = true, apiKey = "fake-key", validationStatus = TvdbValidationStatus.VALID)
        )
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        // TVDB identity resolution fails
        coEvery { tvdbIdentityService.resolveSeriesByRemoteId(any(), any()) } returns null

        val router = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = tvdbIdentityService,
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(contentId = "tt0000000", contentType = ContentType.SERIES)
        )

        // Provider falls back to TMDB
        assertEquals(TvProvider.TMDB, decision.provider)

        // Diagnostics include TVDB_FALLBACK_TMDB with correct event name
        val fallbackDiagnostic = decision.diagnostics.find {
            it.reason == TvMetadataDecisionReason.TVDB_FALLBACK_TMDB
        }
        assertNotNull(
            "Diagnostics must include TVDB_FALLBACK_TMDB when TVDB identity is missing",
            fallbackDiagnostic
        )
        assertEquals(
            "tvdb_fallback_tmdb",
            TvMetadataDecisionReason.TVDB_FALLBACK_TMDB.eventName
        )
    }

    @Test
    fun `diagnostic event names match expected strings`() {
        // Verify all Phase 9 diagnostic event name strings are correctly defined
        assertEquals("tvdb_season_type_present", TvMetadataDecisionReason.TVDB_SEASON_TYPE_PRESENT.eventName)
        assertEquals("tvdb_canonical_trakt_numbering_used", TvMetadataDecisionReason.TVDB_CANONICAL_TRAKT_NUMBERING_USED.eventName)
        assertEquals("tvdb_alternate_order_preserved", TvMetadataDecisionReason.TVDB_ALTERNATE_ORDER_PRESERVED.eventName)
        assertEquals("tvdb_advanced_surface_success", TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_SUCCESS.eventName)
        assertEquals("tvdb_advanced_surface_missing", TvMetadataDecisionReason.TVDB_ADVANCED_SURFACE_MISSING.eventName)
        assertEquals("tmdb_tv_skipped", TvMetadataDecisionReason.TMDB_TV_SKIPPED.eventName)
        assertEquals("tvdb_fallback_tmdb", TvMetadataDecisionReason.TVDB_FALLBACK_TMDB.eventName)
        assertEquals("tvdb_trailer_success", TvMetadataDecisionReason.TVDB_TRAILER_SUCCESS.eventName)
        assertEquals("tvdb_trailer_missing", TvMetadataDecisionReason.TVDB_TRAILER_MISSING.eventName)
        assertEquals("tvdb_trailer_unusable_url", TvMetadataDecisionReason.TVDB_TRAILER_UNUSABLE_URL.eventName)
        assertEquals("tmdb_trailer_fallback", TvMetadataDecisionReason.TMDB_TRAILER_FALLBACK.eventName)
    }
}
