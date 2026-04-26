package com.nexio.tv.core.tvdb

import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbMergeAlias
import com.nexio.tv.data.local.TvdbMergeAliasStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.TvdbValidationStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Credential health gating tests (D-08) and merge alias read-path tests (D-02).
 *
 * Tests encode:
 * - Invalid credentials block new TVDB calls without purging cached TVDB data
 * - Valid credentials re-enable TVDB calls
 * - Invalid credential diagnostic contains no secret material
 * - Duplicate merge source IDs resolve to target before TVDB fetch
 */
class TvdbCredentialHealthTest {

    private val validTvdbSettings = TvdbSettings(
        enabled = true,
        apiKey = "test-key",
        validationStatus = TvdbValidationStatus.VALID
    )

    private val invalidTvdbSettings = TvdbSettings(
        enabled = true,
        apiKey = "test-key",
        validationStatus = TvdbValidationStatus.INVALID
    )

    private fun tvdbSettingsStore(settings: TvdbSettings = validTvdbSettings): TvdbSettingsDataStore {
        return mockk(relaxed = true) {
            every { this@mockk.settings } returns flowOf(settings)
        }
    }

    private fun tmdbSettingsStore(active: Boolean = true): TmdbSettingsDataStore {
        return mockk(relaxed = true) {
            every { this@mockk.settings } returns flowOf(
                com.nexio.tv.domain.model.TmdbSettings(
                    enabled = active,
                    apiKey = if (active) "test-tmdb-key" else "",
                    useBasicInfo = true,
                    useArtwork = true,
                    useDetails = true
                )
            )
        }
    }

    // ── D-08: Invalid credentials block new TVDB calls ──────────────────

    @Test
    fun `invalid credentials block new tvdb calls without cache purge`() = runTest {
        val credentialHealth = TvdbCredentialHealth(
            settingsDataStore = tvdbSettingsStore(),
            diagnosticsRecorder = mockk(relaxUnitFun = true)
        )
        // Mark invalid
        credentialHealth.markInvalid("Authentication failed")

        // canCallTvdb must be false
        assertFalse("Invalid credentials should block TVDB calls", credentialHealth.canCallTvdb())

        // Now test through the router: TVDB API must NOT be called
        val tvdbApi = mockk<TvdbApi>()
        val cachedData = TvMetadataEnrichment(
            seriesTvdbId = 12345,
            localizedTitle = "Cached With Invalid Creds",
            description = "Still available from cache"
        )
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        every {
            metadataDiskCacheStore.readTvdbEnrichment(
                seriesId = any(),
                recordKind = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns cachedData

        val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>(relaxUnitFun = true)

        val tvdbMetadataService = TvdbMetadataService(
            tvdbApi = tvdbApi,
            authService = mockk(relaxed = true) { coEvery { bearerToken() } returns "Bearer test" },
            posterRatingsUrlResolver = mockk(relaxed = true),
            metadataDiskCacheStore = metadataDiskCacheStore,
            seasonOrderMapper = mockk(relaxed = true),
            advancedMetadataMapper = mockk(relaxed = true),
            mergeAliasStore = mockk(relaxed = true) { coEvery { resolveAlias(any(), any()) } returns null },
            credentialHealth = credentialHealth,
            diagnosticsRecorder = diagnosticsRecorder,
            integrationRuntime = passThroughTestRuntime()
        )

        val router = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsStore(),
            tmdbSettingsDataStore = tmdbSettingsStore(),
            tvdbIdentityService = mockk {
                coEvery { resolveSeriesByRemoteId(any(), any()) } returns TvdbSeriesIdentity(
                    tvdbId = 12345,
                    remoteIds = emptyMap()
                )
            },
            tvdbMetadataService = tvdbMetadataService,
            tmdbService = mockk(relaxed = true),
            tmdbMetadataService = mockk(relaxed = true),
            credentialHealth = credentialHealth,
            diagnosticsRecorder = diagnosticsRecorder
        )

        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = com.nexio.tv.domain.model.ContentType.SERIES
            )
        )

        // Cached data should be served (not blanked)
        assertNotNull("Cached data should be served with invalid credentials", decision.value)
        assertEquals("Cached With Invalid Creds", decision.value?.localizedTitle)

        // TVDB API must NOT have been called (network blocked)
        coVerify(exactly = 0) { tvdbApi.getSeriesExtended(any(), any(), any(), any()) }

        // STALE_CACHE_SERVED and/or INVALID_CREDENTIALS diagnostic recorded
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.INVALID_CREDENTIALS ||
                    it.reason == TvdbReliabilityReason.STALE_CACHE_SERVED
            })
        }
    }

    // ── D-08: Valid credentials allow calls again ───────────────────────

    @Test
    fun `valid credentials allow tvdb calls again`() = runTest {
        val credentialHealth = TvdbCredentialHealth(
            settingsDataStore = tvdbSettingsStore(),
            diagnosticsRecorder = mockk(relaxUnitFun = true)
        )
        // Mark invalid then re-mark valid
        credentialHealth.markInvalid("Auth failed")
        assertFalse(credentialHealth.canCallTvdb())

        credentialHealth.markValid()
        assertTrue("Valid credentials should re-enable TVDB calls", credentialHealth.canCallTvdb())
    }

    // ── T-10-04-02: No secret material in invalid credential diagnostic ─

    @Test
    fun `invalid credential diagnostic contains no secret material`() = runTest {
        val recordedDiagnostics = mutableListOf<TvdbReliabilityDiagnostic>()
        val diagnosticsRecorder = object : TvdbDiagnosticsRecorder {
            override suspend fun record(diagnostic: TvdbReliabilityDiagnostic) {
                recordedDiagnostics.add(diagnostic)
            }
        }

        val credentialHealth = TvdbCredentialHealth(
            settingsDataStore = tvdbSettingsStore(),
            diagnosticsRecorder = diagnosticsRecorder
        )

        // Mark invalid with a message that includes secret-looking content
        credentialHealth.markInvalid("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9 failed apiKey=abc123")

        assertTrue("Diagnostic should be recorded", recordedDiagnostics.isNotEmpty())
        val diagnostic = recordedDiagnostics.first()
        assertEquals(TvdbReliabilityReason.INVALID_CREDENTIALS, diagnostic.reason)

        // Verify sanitized version has no secrets
        val sanitized = diagnostic.sanitized()
        val fields = sanitized.structuredLogFields()
        fields.values.forEach { value ->
            assertFalse(
                "Diagnostic field should not contain bearer token: $value",
                value.contains("eyJhbGciOiJIUzI1NiJ9")
            )
            assertFalse(
                "Diagnostic field should not contain API key: $value",
                value.contains("abc123")
            )
        }
    }

    // ── D-02: Duplicate merge source ID resolves to target ──────────────

    @Test
    fun `duplicate merge source id resolves to target before tvdb fetch`() = runTest {
        val credentialHealth = mockk<TvdbCredentialHealth>()
        coEvery { credentialHealth.canCallTvdb() } returns true

        val mergeAliasStore = mockk<TvdbMergeAliasStore>()
        // Series 999 was merged to 12345
        coEvery { mergeAliasStore.resolveAlias("series", 999) } returns TvdbMergeAlias(
            fromEntityType = "series",
            fromId = 999,
            toEntityType = "series",
            toId = 12345,
            updatedAtMs = System.currentTimeMillis()
        )
        // Target ID has no further alias
        coEvery { mergeAliasStore.resolveAlias("series", 12345) } returns null

        val cachedData = TvMetadataEnrichment(
            seriesTvdbId = 12345,
            localizedTitle = "Merged Target Title"
        )
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        // Only the target ID (12345) has cached data
        every {
            metadataDiskCacheStore.readTvdbEnrichment(
                seriesId = 12345,
                recordKind = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns cachedData
        every {
            metadataDiskCacheStore.readTvdbEnrichment(
                seriesId = 999,
                recordKind = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns null

        val diagnosticsRecorder = mockk<TvdbDiagnosticsRecorder>(relaxUnitFun = true)

        val tvdbMetadataService = TvdbMetadataService(
            tvdbApi = mockk(relaxed = true),
            authService = mockk(relaxed = true) { coEvery { bearerToken() } returns "Bearer test" },
            posterRatingsUrlResolver = mockk(relaxed = true),
            metadataDiskCacheStore = metadataDiskCacheStore,
            seasonOrderMapper = mockk(relaxed = true),
            advancedMetadataMapper = mockk(relaxed = true),
            mergeAliasStore = mergeAliasStore,
            credentialHealth = credentialHealth,
            diagnosticsRecorder = diagnosticsRecorder,
            integrationRuntime = passThroughTestRuntime()
        )

        // Request for the OLD duplicate ID (999)
        val identity = TvdbSeriesIdentity(tvdbId = 999, remoteIds = emptyMap())
        val enrichment = tvdbMetadataService.fetchSeriesEnrichment(identity)

        // Should resolve alias and return the MERGED target's cached data
        assertNotNull("Should return merged target's cached data", enrichment)
        assertEquals("Merged Target Title", enrichment?.localizedTitle)
        assertEquals(12345, enrichment?.seriesTvdbId)

        // Verify alias resolution was called
        coVerify(atLeast = 1) { mergeAliasStore.resolveAlias("series", 999) }
    }
}
