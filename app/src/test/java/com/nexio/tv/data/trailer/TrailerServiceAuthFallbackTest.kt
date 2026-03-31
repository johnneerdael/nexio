package com.nexio.tv.data.trailer

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TmdbApi
import com.nexio.tv.data.remote.api.TrailerApi
import com.nexio.tv.data.trailer.helper.TrailerAvailabilityService
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
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

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
    fun `signed in helper miss falls back to local extractor`() = runTest {
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
        coEvery { trailerAvailabilityService.isSignedIn() } returns true
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
        coVerify(exactly = 1) { trailerAvailabilityService.resolveAuthenticatedYouTubePlayback(youtubeUrl) }
        coVerify(exactly = 1) { inAppYouTubeExtractor.extractPlaybackSource(youtubeUrl) }
    }
}
