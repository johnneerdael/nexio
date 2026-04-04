package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TmdbVideoResult
import com.nexio.tv.data.remote.api.TmdbVideosResponse
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.data.trailer.helper.TrailerAvailabilityService
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.StreamRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceAuthFallbackTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `signed out playback uses local extractor without helper`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo01"
        val localSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/stream.m3u8",
            audioUrl = "https://audio.example/stream.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returns false
        coEvery {
            trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl)
        } returns null
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returns localSource

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val resolved = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(localSource, resolved)
        coVerify(exactly = 0) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
    }

    @Test
    fun `signed in playback prefers native extractor before helper`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo02"
        val nativeSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/native.m3u8",
            audioUrl = "https://audio.example/native.m4a"
        )
        val helperSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/helper.m3u8",
            audioUrl = "https://audio.example/helper.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returns true
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returns nativeSource
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) } returns helperSource

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val resolved = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(nativeSource, resolved)
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
        coVerify(exactly = 0) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
    }

    @Test
    fun `tmdb movie trailer lookup falls back to english when localized trailer metadata filters out`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>()
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>(relaxed = true)

        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(apiKey = "tmdb-key"))
        every { tmdbMetadataService.currentTmdbLanguageTag() } returns "nl-NL"
        every {
            metadataDiskCacheStore.readTmdbTitleVideos(
                tmdbId = 550,
                mediaType = "movie",
                languageTag = any(),
                providerToken = any()
            )
        } returns null

        coEvery { trailerAvailabilityService.isSignedIn() } returns false
        coEvery {
            tmdbApi.getMovieVideos(550, "tmdb-key", "nl-NL")
        } returns Response.success(
            TmdbVideosResponse(
                id = 550,
                results = listOf(
                    TmdbVideoResult(
                        iso6391 = "nl",
                        iso31661 = "NL",
                        name = "Nederlandse trailer",
                        key = "dutchtrail01",
                        site = "YouTube",
                        size = 1080,
                        type = "Trailer",
                        official = true,
                        publishedAt = "2024-01-01T00:00:00Z",
                        id = "nl-trailer"
                    )
                )
            )
        )
        coEvery {
            tmdbApi.getMovieVideos(550, "tmdb-key", "en-US")
        } returns Response.success(
            TmdbVideosResponse(
                id = 550,
                results = listOf(
                    TmdbVideoResult(
                        iso6391 = "en",
                        iso31661 = "US",
                        name = "English trailer",
                        key = "englishtr01",
                        site = "YouTube",
                        size = 1080,
                        type = "Trailer",
                        official = true,
                        publishedAt = "2024-01-02T00:00:00Z",
                        id = "en-trailer"
                    )
                )
            )
        )
        coEvery {
            inAppYouTubeExtractor.extractPlaybackSource("https://www.youtube.com/watch?v=englishtr01")
        } returns TrailerPlaybackSource(
            videoUrl = "https://video.example/english.m3u8",
            audioUrl = "https://audio.example/english.m4a"
        )

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val resolved = service.getTrailerPlaybackSourceFromTmdbId(
            tmdbId = "550",
            type = "movie",
            title = "Fight Club",
            year = "1999"
        )

        assertEquals("https://video.example/english.m3u8", resolved?.videoUrl)
        coVerifyOrder {
            tmdbApi.getMovieVideos(550, "tmdb-key", "nl-NL")
            tmdbApi.getMovieVideos(550, "tmdb-key", "en-US")
        }
        coVerify(exactly = 1) {
            inAppYouTubeExtractor.extractPlaybackSource("https://www.youtube.com/watch?v=englishtr01")
        }
    }

    @Test
    fun `signed in native miss falls back to helper after native attempt`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo03"
        val helperSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/helper-fallback.m3u8",
            audioUrl = "https://audio.example/helper-fallback.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returns true
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returns null
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) } returns helperSource

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val resolved = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(helperSource, resolved)
        coVerifyOrder {
            inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
            trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl)
        }
    }

    @Test
    fun `non english native trailer is rejected before helper fallback`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo07"
        val helperSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/helper-should-not-run.m3u8",
            audioUrl = "https://audio.example/helper-should-not-run.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returns true
        coEvery {
            inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl)
        } throws NonEnglishYouTubeTrailerException(
            languageCode = "hi",
            trailerTitle = "Dubbed trailer"
        )
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) } returns helperSource

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val resolved = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertNull(resolved)
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
        coVerify(exactly = 0) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
    }

    @Test
    fun `cached native playback source is reused while signed in`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo04"
        val nativeSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/cached-native.m3u8",
            audioUrl = "https://audio.example/cached-native.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returnsMany listOf(false, true)
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returns nativeSource
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) } returns null

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(nativeSource, first)
        assertEquals(nativeSource, second)
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
        coVerify(exactly = 0) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
    }

    @Test
    fun `cached helper playback source is reused while signed in`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo05"
        val helperSource = TrailerPlaybackSource(
            videoUrl = "https://video.example/cached-helper.m3u8",
            audioUrl = "https://audio.example/cached-helper.m4a"
        )
        coEvery { trailerAvailabilityService.isSignedIn() } returns true
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returns null
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) } returns helperSource

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = Clock.fixed(Instant.parse("2026-04-01T00:00:00Z"), ZoneOffset.UTC)
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(helperSource, first)
        assertEquals(helperSource, second)
        coVerify(exactly = 1) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
    }

    @Test
    fun `expired cached youtube sources are discarded`() = runTest {
        val trailerApi = mockk<TrailerApi>(relaxed = true)
        val tmdbApi = mockk<TmdbApi>(relaxed = true)
        val inAppYouTubeExtractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>(relaxed = true)
        val metadataDiskCacheStore = mockk<MetadataDiskCacheStore>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>(relaxed = true)
        val streamRepository = mockk<StreamRepository>(relaxed = true)
        val trailerAvailabilityService = mockk<TrailerAvailabilityService>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val clock = MutableClock(Instant.parse("2026-04-01T00:00:00Z"))
        val youtubeUrl = "https://www.youtube.com/watch?v=testvideo06"
        val firstSource = TrailerPlaybackSource(videoUrl = "https://video.example/first.m3u8")
        val secondSource = TrailerPlaybackSource(videoUrl = "https://video.example/second.m3u8")
        coEvery { trailerAvailabilityService.isSignedIn() } returns false
        coEvery { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(any()) } returns null
        coEvery { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) } returnsMany listOf(firstSource, secondSource)

        val service = TrailerService(
            trailerApi = trailerApi,
            tmdbApi = tmdbApi,
            inAppYouTubeExtractor = inAppYouTubeExtractor,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            metadataDiskCacheStore = metadataDiskCacheStore,
            tmdbMetadataService = tmdbMetadataService,
            addonRepository = addonRepository,
            streamRepository = streamRepository,
            trailerAvailabilityService = trailerAvailabilityService,
            clock = clock
        )

        val first = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)
        clock.advance(Duration.ofHours(4))
        val second = service.getTrailerPlaybackSourceFromYouTubeUrl(youtubeUrl)

        assertEquals(firstSource, first)
        assertEquals(secondSource, second)
        coVerify(exactly = 2) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
        assertNull(second?.audioUrl)
    }

    private class MutableClock(initialInstant: Instant) : Clock() {
        private var currentInstant: Instant = initialInstant

        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = currentInstant

        fun advance(duration: Duration) {
            currentInstant = currentInstant.plus(duration)
        }
    }
}
