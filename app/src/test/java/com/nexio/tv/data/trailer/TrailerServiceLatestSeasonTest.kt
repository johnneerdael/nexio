package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import com.nexio.tv.data.integration.trailer.TrailerBackendProvider
import com.nexio.tv.data.integration.trailer.TrailerTmdbProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbDetailsResponse
import com.nexio.tv.data.remote.api.TmdbSeasonSummary
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.remote.api.TmdbVideosResponse
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Verifies that [TrailerService.resolveTrailer] auto-detects the latest aired season for TV shows
 * when no explicit seasonNumber is provided.
 *
 * Clock is fixed at 2026-04-01 UTC. Past air dates are anything on or before 2026-03-31.
 */
class TrailerServiceLatestSeasonTest {

    // Clock fixed at 2026-04-01 — air dates on/before 2026-03-31 count as "aired"
    private val fixedClock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)

    private val trailerKey = "trailer_key_s3"
    private val trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
    private val playbackSource = TrailerPlaybackSource(
        videoUrl = "https://video.example/s3.m3u8",
        audioUrl = null
    )

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun buildService(
        tmdbIntegrationProvider: TmdbIntegrationProvider,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        tmdbMetadataService: TmdbMetadataService,
    ): TrailerService = TrailerService(
        trailerBackendProvider = mockk<TrailerBackendProvider>(relaxed = true),
        inAppYouTubeExtractor = inAppYouTubeExtractor,
        tmdbMetadataService = tmdbMetadataService,
        trailerTmdbProvider = TrailerTmdbProvider(
            tmdbIntegrationProvider = tmdbIntegrationProvider,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore
        ),
        addonRepository = mockk(relaxed = true),
        streamRepository = mockk(relaxed = true),
        clock = fixedClock
    )

    // Shared stubs needed by all tests for the disk-cache and auth check
    private fun stubCommonMocks(
        tmdbIntegrationProvider: TmdbIntegrationProvider,
        metadataDiskCacheStore: MetadataDiskCacheStore,
        tmdbMetadataService: TmdbMetadataService,
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        inAppYouTubeExtractor: InAppYouTubeExtractor,
    ) {
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(apiKey = "tmdb-key"))
        every { tmdbMetadataService.currentTmdbLanguageTag() } returns "en-US"
        // Disk cache always misses so TMDB API is always consulted
        every {
            metadataDiskCacheStore.readTmdbTitleVideos(any(), any(), any(), any())
        } returns null
        every {
            metadataDiskCacheStore.readTmdbSeasonVideos(any(), any(), any(), any())
        } returns null
        // YouTube extractor resolves any URL to our test playback source
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(any(), any()) } returns playbackSource
        // Default: series-level TV videos return empty so tests that don't care don't fail
        coEvery { tmdbIntegrationProvider.fetchTvVideos(any(), any()) } returns TmdbVideosResponse(
            id = 0, results = emptyList()
        )
    }

    private fun makeSeasonSummary(
        number: Int,
        airDate: String?,
        episodeCount: Int
    ) = TmdbSeasonSummary(seasonNumber = number, airDate = airDate, episodeCount = episodeCount)

    private fun makeTrailerVideo(key: String) = TmdbVideoResult(
        iso6391 = "en",
        key = key,
        site = "YouTube",
        size = 1080,
        type = "Trailer",
        official = true,
        publishedAt = "2025-01-01T00:00:00Z"
    )

    // ────────────────────────────────────────────────────────────────────────
    // 1. Auto-detects the latest aired season
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `auto-detects latest aired season when no explicit season provided`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        // Seasons: 1 (aired), 3 (aired, latest), 4 (future)
        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(
                makeSeasonSummary(1, "2020-01-01", 10),
                makeSeasonSummary(3, "2025-01-01", 8),
                makeSeasonSummary(4, "2027-01-01", 0) // future — must be ignored
            )
        )
        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(tvId = 12345, seasonNumber = 3, normalizedLanguage = "en-US")
        } returns TmdbVideosResponse(id = 12345, results = listOf(makeTrailerVideo(trailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        coVerify(exactly = 1) { tmdbIntegrationProvider.fetchSeasonVideos(12345, 3, "en-US") }
        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchTvVideos(any(), any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 2. Explicit season always wins — detection is skipped
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `explicit seasonNumber bypasses auto-detection`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(tvId = 12345, seasonNumber = 2, normalizedLanguage = "en-US")
        } returns TmdbVideosResponse(id = 12345, results = listOf(makeTrailerVideo(trailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series", seasonNumber = 2)

        // fetchTvDetails must NOT be called when an explicit season is provided
        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchTvDetails(any(), any()) }
        coVerify(exactly = 1) { tmdbIntegrationProvider.fetchSeasonVideos(12345, 2, "en-US") }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 3. All seasons in the future → falls back to series-level videos
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `falls back to series videos when all seasons have future air dates`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(
                makeSeasonSummary(1, "2027-01-01", 10),
                makeSeasonSummary(2, "2028-01-01", 10)
            )
        )

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchSeasonVideos(any(), any(), any()) }
        coVerify(atLeast = 1) { tmdbIntegrationProvider.fetchTvVideos(12345, any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 4. Season 0 (specials) is ignored even when it has a past air date
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `season 0 specials are ignored`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(makeSeasonSummary(0, "2020-01-01", 5))
        )

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchSeasonVideos(any(), any(), any()) }
        coVerify(atLeast = 1) { tmdbIntegrationProvider.fetchTvVideos(12345, any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5. Null/blank airDate on a season is treated as unaired
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `season with null air date is excluded and falls back to earlier valid season`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(
                makeSeasonSummary(1, "2020-01-01", 10),
                makeSeasonSummary(2, null, 8) // no air date → treated as unaired
            )
        )
        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(tvId = 12345, seasonNumber = 1, normalizedLanguage = "en-US")
        } returns TmdbVideosResponse(id = 12345, results = listOf(makeTrailerVideo(trailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        coVerify(exactly = 1) { tmdbIntegrationProvider.fetchSeasonVideos(12345, 1, "en-US") }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 6. fetchTvDetails throws → graceful fallback to series videos
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `getTvDetails exception falls back to series-level videos`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery { tmdbIntegrationProvider.fetchTvDetails(any(), any()) } throws java.io.IOException("network error")

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        // Should not throw; falls back to series-level
        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchSeasonVideos(any(), any(), any()) }
        coVerify(atLeast = 1) { tmdbIntegrationProvider.fetchTvVideos(12345, any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 7. Non-TV type skips detection entirely
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `movie type skips season detection`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        every {
            metadataDiskCacheStore.readTmdbTitleVideos(any(), any(), any(), any())
        } returns null

        coEvery {
            tmdbIntegrationProvider.fetchMovieVideos(movieId = 99999, normalizedLanguage = any())
        } returns TmdbVideosResponse(id = 99999, results = listOf(makeTrailerVideo(trailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Movie", tmdbId = "99999", type = "movie")

        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchTvDetails(any(), any()) }
        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchSeasonVideos(any(), any(), any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 8. Season detection result is cached — fetchTvDetails called only once
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `season detection result is cached across calls for the same show`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(makeSeasonSummary(2, "2024-01-01", 10))
        )
        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(any(), any(), any())
        } returns TmdbVideosResponse(id = 12345, results = listOf(makeTrailerVideo(trailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        // Two calls for the same show with the same args — first hits outer lookupCache on second call
        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")
        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        // fetchTvDetails should only be called once regardless
        coVerify(exactly = 1) { tmdbIntegrationProvider.fetchTvDetails(12345, "en-US") }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 9. Latest season with no TMDB trailer falls back to series-level videos
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `falls back to series videos when latest season has no trailer on TMDB`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        stubCommonMocks(tmdbIntegrationProvider, metadataDiskCacheStore, tmdbMetadataService,
            tmdbSettingsDataStore, inAppYouTubeExtractor)

        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(makeSeasonSummary(3, "2025-01-01", 8))
        )
        // Season 3 has no videos (both language attempts)
        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(tvId = 12345, seasonNumber = 3, normalizedLanguage = "en-US")
        } returns TmdbVideosResponse(id = 12345, results = emptyList())

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        // Must attempt season 3, then fall through to series videos
        coVerify(atLeast = 1) { tmdbIntegrationProvider.fetchSeasonVideos(12345, 3, any()) }
        coVerify(atLeast = 1) { tmdbIntegrationProvider.fetchTvVideos(12345, any()) }
    }

    // ────────────────────────────────────────────────────────────────────────
    // 10. Upgrade scenario: pre-existing series-level disk cache is bypassed
    //
    // Before the upgrade, series-level trailers were stored in the disk cache
    // under readTmdbTitleVideos (old code, seasonNumber=null path).
    // After the upgrade, the new code detects the latest aired season and looks
    // up readTmdbSeasonVideos for that season — a different cache key that was
    // never written — so it misses and fetches the season-specific trailer from
    // TMDB instead of serving the stale series-level one.
    // ────────────────────────────────────────────────────────────────────────

    @Test
    fun `pre-existing series-level disk cache does not prevent latest season trailer from loading after upgrade`() = runTest {
        val tmdbIntegrationProvider = mockk<TmdbIntegrationProvider>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()

        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(apiKey = "tmdb-key"))
        every { tmdbMetadataService.currentTmdbLanguageTag() } returns "en-US"
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(any(), any()) } returns playbackSource

        // Old disk cache has a series-level entry (written by the pre-upgrade code)
        val oldSeriesTrailer = listOf(makeTrailerVideo("old_series_key"))
        every {
            metadataDiskCacheStore.readTmdbTitleVideos(
                tmdbId = 12345,
                mediaType = "tv",
                languageTag = any(),
                providerToken = any()
            )
        } returns oldSeriesTrailer

        // Season-specific disk cache is empty — was never written before the upgrade
        every {
            metadataDiskCacheStore.readTmdbSeasonVideos(
                tmdbId = 12345,
                seasonNumber = any(),
                languageTag = any(),
                providerToken = any()
            )
        } returns null

        // TMDB details reveals show has a season 3 that has aired
        coEvery {
            tmdbIntegrationProvider.fetchTvDetails(tvId = 12345, normalizedLanguage = "en-US")
        } returns TmdbDetailsResponse(
            id = 12345,
            seasons = listOf(
                makeSeasonSummary(1, "2020-01-01", 10),
                makeSeasonSummary(3, "2025-01-01", 8)
            )
        )

        // TMDB returns the new season 3 trailer
        val season3TrailerKey = "season3_trailer_key"
        coEvery {
            tmdbIntegrationProvider.fetchSeasonVideos(tvId = 12345, seasonNumber = 3, normalizedLanguage = "en-US")
        } returns TmdbVideosResponse(id = 12345, results = listOf(makeTrailerVideo(season3TrailerKey)))

        val service = buildService(tmdbIntegrationProvider, inAppYouTubeExtractor, tmdbSettingsDataStore,
            metadataDiskCacheStore, tmdbMetadataService)

        service.resolveTrailer(title = "Test Show", tmdbId = "12345", type = "series")

        // The season 3 video must be fetched from TMDB (disk cache miss for season path)
        coVerify(exactly = 1) { tmdbIntegrationProvider.fetchSeasonVideos(12345, 3, "en-US") }
        // The old series-level TMDB endpoint must NOT be called — season videos take priority
        coVerify(exactly = 0) { tmdbIntegrationProvider.fetchTvVideos(any(), any()) }
        // The extractor must be called with the season 3 trailer URL, not the old series trailer
        coVerify(exactly = 1) {
            inAppYouTubeExtractor.extractPlaybackSource(
                "https://www.youtube.com/watch?v=$season3TrailerKey",
                any()
            )
        }
    }
}
