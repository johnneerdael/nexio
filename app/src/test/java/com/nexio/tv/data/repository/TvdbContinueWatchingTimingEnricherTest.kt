package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityCalculator
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityDiagnosticReason
import com.nexio.tv.core.tvdb.TvdbAirAvailabilityPrecision
import com.nexio.tv.core.tvdb.TvdbSeriesTiming
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbContinueWatchingTimingEnricherTest {

    private val availabilityCalculator = TvdbAirAvailabilityCalculator()

    @Test
    fun `enrich adds exact tvdb availability to next up entry`() = runTest {
        val router = mockk<TvMetadataRouter>()
        val enricher = TvdbContinueWatchingTimingEnricher(router, availabilityCalculator)
        val entry = nextUpEntry()
        val expected = availabilityCalculator.computeAvailability(
            episodeAiredDate = "2026-04-15",
            seriesTiming = TvdbSeriesTiming(
                airsTime = "21:00",
                originalCountry = "usa",
                originalNetwork = "HBO",
                latestNetwork = "HBO",
                platformName = "HBO"
            )
        )

        coEvery { router.fetchEnrichment(any()) } returns seriesDecision(airsTime = "21:00")
        coEvery { router.fetchEpisodeEnrichment(any()) } returns episodeDecision(airDate = "2026-04-15")

        val enriched = enricher.enrich(listOf(entry)).single()

        assertEquals(expected.instantMs, enriched.tvdbAvailabilityInstantMs)
        assertEquals(TvdbAirAvailabilityPrecision.EXACT_INSTANT, enriched.tvdbAvailabilityPrecision)
        assertEquals(expected.sourceZoneId, enriched.tvdbAvailabilitySourceZoneId)
        assertEquals(expected.sourcePolicy, enriched.tvdbAvailabilitySourcePolicy)
        assertNull(enriched.tvdbAvailabilityDiagnosticReason)
        assertEquals(expected.deviceLocalDateTime, enriched.tvdbAvailabilityDeviceLocalDateTime)
        coVerify(exactly = 1) { router.fetchEnrichment(any()) }
        coVerify(exactly = 1) { router.fetchEpisodeEnrichment(any()) }
    }

    @Test
    fun `enrich preserves provider first aired while tvdb exact wins later`() = runTest {
        val router = mockk<TvMetadataRouter>()
        val enricher = TvdbContinueWatchingTimingEnricher(router, availabilityCalculator)
        val providerFirstAiredMs = 1_000L
        val entry = nextUpEntry(firstAired = "2026-04-14", firstAiredMs = providerFirstAiredMs)

        coEvery { router.fetchEnrichment(any()) } returns seriesDecision(airsTime = "21:00")
        coEvery { router.fetchEpisodeEnrichment(any()) } returns episodeDecision(airDate = "2026-04-15")

        val enriched = enricher.enrich(listOf(entry)).single()

        assertEquals(providerFirstAiredMs, enriched.firstAiredMs)
        assertEquals("2026-04-14", enriched.firstAired)
        assertNotEquals(providerFirstAiredMs, enriched.tvdbAvailabilityInstantMs)
        assertEquals(TvdbAirAvailabilityPrecision.EXACT_INSTANT, enriched.tvdbAvailabilityPrecision)
    }

    @Test
    fun `enrich records date only diagnostic when airsTime is missing`() = runTest {
        val router = mockk<TvMetadataRouter>()
        val enricher = TvdbContinueWatchingTimingEnricher(router, availabilityCalculator)
        val entry = nextUpEntry(firstAired = "2026-04-15")

        coEvery { router.fetchEnrichment(any()) } returns seriesDecision(airsTime = null)
        coEvery { router.fetchEpisodeEnrichment(any()) } returns episodeDecision(airDate = "2026-04-15")

        val enriched = enricher.enrich(listOf(entry)).single()

        assertNull(enriched.tvdbAvailabilityInstantMs)
        assertEquals(TvdbAirAvailabilityPrecision.DATE_ONLY, enriched.tvdbAvailabilityPrecision)
        assertEquals(TvdbAirAvailabilityDiagnosticReason.MISSING_AIRS_TIME, enriched.tvdbAvailabilityDiagnosticReason)
        assertEquals("missing_airs_time", enriched.tvdbAvailabilityDiagnosticReason?.code)
    }

    @Test
    fun `enrich leaves non series entries unchanged`() = runTest {
        val router = mockk<TvMetadataRouter>(relaxed = true)
        val enricher = TvdbContinueWatchingTimingEnricher(router, availabilityCalculator)
        val movie = nextUpEntry(contentType = "movie")

        val enriched = enricher.enrich(listOf(movie))

        assertEquals(listOf(movie), enriched)
        coVerify(exactly = 0) { router.fetchEnrichment(any()) }
        coVerify(exactly = 0) { router.fetchEpisodeEnrichment(any()) }
    }

    private fun seriesDecision(airsTime: String?): TvMetadataDecision<TvMetadataEnrichment> {
        return TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                airsTime = airsTime,
                originalCountry = "usa",
                originalNetwork = "HBO",
                latestNetwork = "HBO",
                platformName = "HBO"
            )
        )
    }

    private fun episodeDecision(airDate: String): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> {
        return TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
                (1 to 2) to TvEpisodeMetadata(
                    seasonNumber = 1,
                    episodeNumber = 2,
                    airDate = airDate
                )
            )
        )
    }

    private fun nextUpEntry(
        contentId: String = "tvdb:121361",
        contentType: String = "series",
        firstAired: String? = null,
        firstAiredMs: Long = 0L
    ): TrackingNextUpEntry {
        return TrackingNextUpEntry(
            contentId = contentId,
            contentType = contentType,
            name = "Game of Thrones",
            season = 1,
            episode = 2,
            episodeTitle = "The Kingsroad",
            videoId = "tt0944947:1:2",
            firstAired = firstAired,
            firstAiredMs = firstAiredMs,
            activityAtMs = 123L
        )
    }
}
