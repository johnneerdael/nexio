package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.TvdbDiagnosticsSnapshot
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.local.debugDetailLines
import com.nexio.tv.data.local.settingsStatusLine
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
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbDiagnosticsTest {

    @Test
    fun `fallback diagnostic stores sanitized reason without credential material`() = runTest {
        val dataStore = mockk<TvdbSettingsDataStore>(relaxed = true) {
            every { settings } returns flowOf(TvdbSettings())
        }
        val fallback = TvdbProviderFallback(settingsDataStore = dataStore)

        coEvery {
            dataStore.saveValidationFailure(any(), any())
        } returns Unit

        fallback.recordFallback("401 for key tvdb-key with pin subscriber-pin token tvdb-token")

        coVerify(exactly = 1) {
            dataStore.saveValidationFailure(
                status = TvdbValidationStatus.FALLBACK_ACTIVE,
                lastFailure = TvdbProviderFallback.REASON_AUTH_UNAVAILABLE
            )
        }
    }

    // --- Settings projection tests (plan 10-05) ---

    @Test
    fun `settings projection maps invalid credentials stale cache and refresh failures`() {
        val invalidCred = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.INVALID_CREDENTIALS,
            surface = "settings"
        )
        val staleCacheDiag = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.STALE_CACHE_SERVED,
            surface = "settings"
        )
        val updateFailed = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.UPDATE_REFRESH_FAILED,
            surface = "settings"
        )
        val refFailed = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.REFERENCE_REFRESH_FAILED,
            surface = "settings"
        )

        assertNotNull(invalidCred.userStatusLine())
        assertEquals("TVDB credentials need attention", invalidCred.userStatusLine())
        assertNotNull(staleCacheDiag.userStatusLine())
        assertEquals("TVDB served cached data while refresh is unavailable", staleCacheDiag.userStatusLine())
        assertNotNull(updateFailed.userStatusLine())
        assertEquals("TVDB cache refresh failed", updateFailed.userStatusLine())
        assertNotNull(refFailed.userStatusLine())
        assertEquals("TVDB reference refresh failed", refFailed.userStatusLine())

        // Verify settings status line projection from snapshot
        val snapshot = TvdbDiagnosticsSnapshot(
            lastInvalidCredentialStatus = "invalid_credentials",
            lastStaleCacheStatus = "stale_cache_served",
            lastUpdateRefreshStatus = "update_refresh_failed",
            lastReferenceRefreshStatus = "reference_refresh_failed"
        )
        val statusLine = snapshot.settingsStatusLine()
        assertNotNull(statusLine)
    }

    @Test
    fun `debug diagnostics include skipped tmdb and poster override reasons`() {
        val snapshot = TvdbDiagnosticsSnapshot(
            lastTmdbSkipReason = "TVDB active, TMDB TV fetch skipped",
            lastPosterReason = "poster_ratings_override"
        )

        val debugLines = snapshot.debugDetailLines()
        val joined = debugLines.joinToString(" ")
        assert(joined.contains("TMDB")) { "Debug lines should include TMDB skip reason" }
        assert(joined.contains("poster")) { "Debug lines should include poster override reason" }
    }

    @Test
    fun `debug diagnostics include provider fallback date only and missing airs time reasons`() {
        val snapshot = TvdbDiagnosticsSnapshot(
            lastProviderDecision = "tvdb_provider_chosen",
            lastFallbackReason = "explicit_fallback",
            lastAirtimeReason = "date_only_gating"
        )

        val debugLines = snapshot.debugDetailLines()
        val joined = debugLines.joinToString(" ")
        assert(joined.contains("provider") || joined.contains("Provider")) {
            "Debug lines should include provider decision"
        }
        assert(joined.contains("fallback") || joined.contains("Fallback")) {
            "Debug lines should include fallback reason"
        }
        assert(joined.contains("date_only") || joined.contains("airtime") || joined.contains("Airtime")) {
            "Debug lines should include airtime/date-only reason"
        }
    }

    @Test
    fun `settings projection does not expose debug only provider reasons`() {
        // Provider choice, poster override, date-only gating, missing airsTime,
        // and skipped TMDB TV fetches are Debug-only
        val providerChosen = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TVDB_PROVIDER_CHOSEN,
            surface = "router"
        )
        val posterOverride = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.POSTER_RATINGS_OVERRIDE,
            surface = "poster"
        )
        val dateOnly = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.DATE_ONLY_GATING,
            surface = "air_availability"
        )
        val missingAirs = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.MISSING_AIRS_TIME,
            surface = "air_availability"
        )
        val tmdbSkipped = TvdbReliabilityDiagnostic(
            reason = TvdbReliabilityReason.TMDB_TV_FETCH_SKIPPED,
            surface = "router"
        )

        assertNull(providerChosen.userStatusLine())
        assertNull(posterOverride.userStatusLine())
        assertNull(dateOnly.userStatusLine())
        assertNull(missingAirs.userStatusLine())
        assertNull(tmdbSkipped.userStatusLine())
    }

    @Test
    fun `settings and debug consume existing diagnostics snapshot`() {
        // Verify snapshot created by plan 10-00 has all required fields
        // and both settings and debug projections can consume it
        val snapshot = TvdbDiagnosticsSnapshot(
            lastProviderDecision = "tvdb_provider_chosen",
            lastFallbackReason = "explicit_fallback",
            lastUpdateRefreshStatus = "update_refresh_failed",
            lastReferenceRefreshStatus = "reference_refresh_succeeded",
            lastAirtimeReason = "missing_airs_time",
            lastPosterReason = "poster_ratings_override",
            lastTmdbSkipReason = "tmdb_tv_fetch_skipped",
            lastInvalidCredentialStatus = "invalid_credentials",
            lastStaleCacheStatus = "stale_cache_served"
        )

        // Settings projection should return a non-null status (credential issue takes priority)
        val settingsLine = snapshot.settingsStatusLine()
        assertNotNull(settingsLine)

        // Debug projection should list all nine fields
        val debugLines = snapshot.debugDetailLines()
        assertEquals(9, debugLines.size)
    }
}
