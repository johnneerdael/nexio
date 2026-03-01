package com.nuvio.tv.data.trailer

import android.util.Log
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbVideoResult
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.data.remote.api.TrailerApi
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class TrailerServiceTmdbLanguageTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `tmdb trailer lookup uses user configured language first`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "es"))

        val service = TrailerService(trailerApi, tmdbApi, extractor, tmdbSettingsDataStore)

        coEvery { tmdbApi.getMovieVideos(550, any(), "es") } returns Response.success(
            TmdbVideosResponse(
                id = 550,
                results = listOf(
                    TmdbVideoResult(key = "aaaaaaaaaaa", site = "YouTube", type = "Trailer", official = true, size = 1080)
                )
            )
        )
        coEvery { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=aaaaaaaaaaa") } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/video.mp4")

        val source = service.getTrailerPlaybackSourceFromTmdbId(
            tmdbId = "550",
            type = "movie",
            title = "Fight Club",
            year = "1999"
        )

        assertEquals("https://cdn.example/video.mp4", source?.videoUrl)
        coVerify(exactly = 1) { tmdbApi.getMovieVideos(550, any(), "es") }
        coVerify(exactly = 0) { tmdbApi.getMovieVideos(550, any(), "en-US") }
    }

    @Test
    fun `tmdb trailer lookup falls back to en-US when localized result is empty`() = runTest {
        val trailerApi = mockk<TrailerApi>()
        val tmdbApi = mockk<TmdbApi>()
        val extractor = mockk<InAppYouTubeExtractor>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(language = "fr"))

        val service = TrailerService(trailerApi, tmdbApi, extractor, tmdbSettingsDataStore)

        coEvery { tmdbApi.getMovieVideos(550, any(), "fr") } returns
            Response.success(TmdbVideosResponse(id = 550, results = emptyList()))
        coEvery { tmdbApi.getMovieVideos(550, any(), "en-US") } returns Response.success(
            TmdbVideosResponse(
                id = 550,
                results = listOf(
                    TmdbVideoResult(key = "bbbbbbbbbbb", site = "YouTube", type = "Trailer", official = true, size = 1080)
                )
            )
        )
        coEvery { extractor.extractPlaybackSource("https://www.youtube.com/watch?v=bbbbbbbbbbb") } returns
            TrailerPlaybackSource(videoUrl = "https://cdn.example/video-fallback.mp4")

        val source = service.getTrailerPlaybackSourceFromTmdbId(
            tmdbId = "550",
            type = "movie",
            title = "Fight Club",
            year = "1999"
        )

        assertEquals("https://cdn.example/video-fallback.mp4", source?.videoUrl)
        coVerifyOrder {
            tmdbApi.getMovieVideos(550, any(), "fr")
            tmdbApi.getMovieVideos(550, any(), "en-US")
        }
    }

    @Test
    fun `normalizeTmdbTrailerLanguage keeps region and defaults english`() {
        assertEquals("pt-BR", normalizeTmdbTrailerLanguage("pt_br"))
        assertEquals("ja", normalizeTmdbTrailerLanguage("ja"))
        assertEquals("en-US", normalizeTmdbTrailerLanguage("en"))
        assertEquals("en-US", normalizeTmdbTrailerLanguage(""))
        assertEquals("en-US", normalizeTmdbTrailerLanguage(null))
    }
}
