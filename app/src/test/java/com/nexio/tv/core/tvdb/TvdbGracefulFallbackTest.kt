package com.nexio.tv.core.tvdb

import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.integration.passThroughTestRuntime
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Graceful fallback regression tests for TVDB provider failures (D-07, D-08, D-09).
 *
 * Tests encode:
 * - Last-known-good TVDB data served during outages before explicit fallback
 * - Continue watching preserves cached display metadata during outages
 * - Missing airsTime records field-level fallback while TVDB remains provider
 * - Date-only gating records reason without blanking next-up metadata
 */
class TvdbGracefulFallbackTest {

    private val activeTvdbSettings = TvdbSettings(
        enabled = true,
        apiKey = "test-key",
        validationStatus = TvdbValidationStatus.VALID
    )

    private fun tvdbSettingsStore(settings: TvdbSettings = activeTvdbSettings): TvdbSettingsDataStore {
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

    private fun cachedEnrichment(title: String = "Cached Title"): TvMetadataEnrichment {
        return TvMetadataEnrichment(
            seriesTvdbId = 12345,
            localizedTitle = title,
            description = "Cached description",
            poster = "https://cached-poster.jpg",
            backdrop = "https://cached-backdrop.jpg"
        )
    }

    // ── D-07: Outage serves last-known-good ─────────────────────────────

    @Test
    fun `outage serves last known good tv detail before explicit fallback`() = runTest {
        // Arrange: TVDB active, cached enrichment exists, TVDB API throws on network call
        val tvdbSettingsDataStore = tvdbSettingsStore()
        val credentialHealth = mockk<TvdbCredentialHealth>()
        coEvery { credentialHealth.canCallTvdb() } returns true

        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val cachedData = cachedEnrichment("Last Known Good Title")
        // First cache read returns null (cache miss, forces API call),
        // second read returns stale data (D-07 stale re-check after API failure)
        every {
            metadataDiskCacheStore.readTvdbEnrichment(
                seriesId = any(),
                recordKind = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns null andThen cachedData

        val tvdbApi = mockk<TvdbApi>()
        // API throws (outage simulation)
        coEvery { tvdbApi.getSeriesExtended(any(), any(), any(), any()) } throws
            java.io.IOException("TVDB service unavailable")

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
            tvdbSettingsDataStore = tvdbSettingsDataStore,
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

        // Act
        val decision = router.fetchEnrichment(
            TvMetadataRequest(
                contentId = "tt0944947",
                contentType = com.nexio.tv.domain.model.ContentType.SERIES
            )
        )

        // Assert: Last-known-good cached data served, provider remains TVDB
        assertNotNull("Should return cached data during outage", decision.value)
        assertEquals("Last Known Good Title", decision.value?.localizedTitle)
        assertEquals(TvProvider.TVDB, decision.provider)

        // Verify STALE_CACHE_SERVED diagnostic recorded
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.STALE_CACHE_SERVED
            })
        }
    }

    // ── D-07: Continue watching outage ──────────────────────────────────

    @Test
    fun `continue watching outage preserves cached display metadata`() = runTest {
        // When TVDB refresh fails during continue watching enrichment,
        // previously cached TVDB metadata must be preserved (not blanked).
        val tvdbSettingsDataStore = tvdbSettingsStore()
        val credentialHealth = mockk<TvdbCredentialHealth>()
        coEvery { credentialHealth.canCallTvdb() } returns true

        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val cachedData = cachedEnrichment("Cached Show for Continue Watching")
        every {
            metadataDiskCacheStore.readTvdbEnrichment(
                seriesId = any(),
                recordKind = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns cachedData

        val tvdbApi = mockk<TvdbApi>()
        coEvery { tvdbApi.getSeriesExtended(any(), any(), any(), any()) } throws
            java.io.IOException("Network timeout")

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
            tvdbSettingsDataStore = tvdbSettingsDataStore,
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

        // Continue watching display metadata preserved from cache
        assertNotNull("Cached metadata must be preserved during outage", decision.value)
        assertEquals("Cached Show for Continue Watching", decision.value?.localizedTitle)
        assertEquals(TvProvider.TVDB, decision.provider)
    }

    // ── D-09: Missing airsTime field-level fallback ─────────────────────

    @Test
    fun `missing airs time records field level fallback while tvdb remains provider`() = runTest {
        val tvdbSettingsDataStore = tvdbSettingsStore()
        val credentialHealth = mockk<TvdbCredentialHealth>()
        coEvery { credentialHealth.canCallTvdb() } returns true

        // Cached enrichment with no airsTime
        val cachedData = cachedEnrichment().copy(airsTime = null)
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
            tvdbApi = mockk(relaxed = true),
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
            tvdbSettingsDataStore = tvdbSettingsDataStore,
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

        // TVDB remains provider even with missing airsTime
        assertEquals(TvProvider.TVDB, decision.provider)
        assertNotNull(decision.value)

        // MISSING_AIRS_TIME diagnostic recorded
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.MISSING_AIRS_TIME
            })
        }
    }

    // ── D-09: Date-only gating records reason ───────────────────────────

    @Test
    fun `date only gating records reason without blanking next up metadata`() = runTest {
        val tvdbSettingsDataStore = tvdbSettingsStore()
        val credentialHealth = mockk<TvdbCredentialHealth>()
        coEvery { credentialHealth.canCallTvdb() } returns true

        // Enrichment present with airsTime but used for date-only gating
        val cachedData = cachedEnrichment().copy(
            airsTime = null,
            airsDays = mapOf("monday" to true)
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
            tvdbApi = mockk(relaxed = true),
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
            tvdbSettingsDataStore = tvdbSettingsDataStore,
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

        // Metadata not blanked -- title and other fields present
        assertNotNull(decision.value)
        assertEquals("Cached Title", decision.value?.localizedTitle)
        assertEquals(TvProvider.TVDB, decision.provider)

        // DATE_ONLY_GATING diagnostic recorded because airsTime is missing
        // but airsDays is present (indicates scheduling exists but time is missing)
        coVerify(atLeast = 1) {
            diagnosticsRecorder.record(match {
                it.reason == TvdbReliabilityReason.MISSING_AIRS_TIME ||
                    it.reason == TvdbReliabilityReason.DATE_ONLY_GATING
            })
        }
    }
}
