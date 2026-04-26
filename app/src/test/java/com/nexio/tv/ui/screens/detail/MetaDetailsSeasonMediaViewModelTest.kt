package com.nexio.tv.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvdbIdentityService
import com.nexio.tv.core.tvdb.TvdbMetadataService
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.repository.ReviewsRepository
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TvdbSettings
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsSeasonMediaViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `season short press stays a playback no-op when no season trailer exists`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns false
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns TrailerResolutionResult.Playback(
            TrailerPlaybackSource(
                videoUrl = "https://example.com/series.m3u8",
                audioUrl = "https://example.com/series-audio.m4a"
            )
        )
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns null

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.trailerUrl)

        viewModel.onEvent(MetaDetailsEvent.OnSeasonShortPress(2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedSeason)
        assertEquals(null, state.trailerUrl)
        assertEquals(null, state.trailerAudioUrl)
        assertFalse(state.isTrailerPlaying)
        assertFalse(state.showTrailerControls)
        assertFalse(state.hideLogoDuringTrailer)
        assertFalse(state.selectedSeasonHasPlayableTrailerMedia)
        coVerify(exactly = 0) {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }
        coVerify(atLeast = 1) {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        }
    }

    @Test
    fun `view model enables universal streamer mode when no addons are installed`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>(relaxed = true)
        val viewModel = buildViewModel(
            trailerService = trailerService,
            installedAddons = emptyList()
        )
        advanceUntilIdle()

        assertEquals(0, viewModel.uiState.value.installedAddonsCount)
        assertTrue(viewModel.uiState.value.universalStreamerModeEnabled)
    }

    @Test
    fun `view model disables universal streamer mode when an addon is installed`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>(relaxed = true)
        val viewModel = buildViewModel(
            trailerService = trailerService,
            installedAddons = listOf(
                com.nexio.tv.domain.model.Addon(
                    id = "addon-1",
                    name = "Example",
                    displayName = "Example",
                    version = "1.0.0",
                    description = null,
                    logo = null,
                    baseUrl = "https://example.com",
                    catalogs = emptyList(),
                    types = listOf(ContentType.MOVIE),
                    resources = emptyList()
                )
            )
        )
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.value.installedAddonsCount)
        assertFalse(viewModel.uiState.value.universalStreamerModeEnabled)
    }

    @Test
    fun `season short press plays teaser when season metadata resolves trailer availability on demand`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns false
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } answers {
            val season = thirdArg<Int?>()
            if (season == 7) {
                SeasonMediaAvailability(
                    hasTrailerOrTeaser = true,
                    hasRecap = false
                )
            } else {
                SeasonMediaAvailability(
                    hasTrailerOrTeaser = false,
                    hasRecap = false
                )
            }
        }
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 7,
                contentId = any()
            )
        } returns TrailerPlaybackSource(
            videoUrl = "https://example.com/season-teaser.m3u8",
            audioUrl = "https://example.com/season-teaser-audio.m4a"
        )

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnSeasonShortPress(7))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(7, state.selectedSeason)
        assertTrue(state.selectedSeasonHasPlayableTrailerMedia)
        assertEquals("https://example.com/season-teaser.m3u8", state.trailerUrl)
        assertEquals("https://example.com/season-teaser-audio.m4a", state.trailerAudioUrl)
        assertTrue(state.isTrailerPlaying)
        assertTrue(state.showTrailerControls)
        assertTrue(state.hideLogoDuringTrailer)
        coVerify(atLeast = 1) {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = 7,
                contentId = any()
            )
        }
        coVerify(exactly = 1) {
            trailerService.getSeasonTrailerPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 7,
                contentId = any()
            )
        }
    }

    @Test
    fun `tmdb episode enrichment hydrates per episode runtime before detail playback`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>(relaxed = true)
        coEvery { trailerService.getTitleMediaAvailability(any(), any(), any(), any()) } returns false
        coEvery { trailerService.getSeasonMediaAvailability(any(), any(), any(), any()) } returns SeasonMediaAvailability()
        val tmdbService = mockk<TmdbService>()
        coEvery { tmdbService.tmdbToImdb(any(), any()) } returns null
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns "1399"
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        coEvery {
            tmdbMetadataService.fetchEnrichment(
                tmdbId = any(),
                contentType = any(),
                language = any()
            )
        } returns null
        coEvery {
            tmdbMetadataService.fetchEpisodeEnrichment(
                tmdbId = "1399",
                seasonNumbers = any(),
                language = any()
            )
        } returns mapOf(
            (1 to 1) to com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment(
                tmdbEpisodeId = 1,
                voteAverage = null,
                title = null,
                overview = null,
                thumbnail = null,
                airDate = null,
                runtimeMinutes = 62
            ),
            (2 to 1) to com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment(
                tmdbEpisodeId = 2,
                voteAverage = null,
                title = null,
                overview = null,
                thumbnail = null,
                airDate = null,
                runtimeMinutes = 58
            )
        )

        val viewModel = buildViewModel(
            trailerService = trailerService,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            tmdbSettings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        )

        advanceUntilIdle()

        val meta = viewModel.uiState.value.meta
        assertEquals(62, meta?.videos?.firstOrNull { it.season == 1 && it.episode == 1 }?.runtime)
        assertEquals(58, meta?.videos?.firstOrNull { it.season == 2 && it.episode == 1 }?.runtime)
    }

    @Test
    fun `failed season trailer attempt preserves external cta without reviving pending launch`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns TrailerResolutionResult.External("https://youtube.com/watch?v=series")
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns null

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        advanceUntilIdle()
        assertEquals(
            "https://youtube.com/watch?v=series",
            viewModel.uiState.value.pendingExternalTrailerUrl
        )

        viewModel.onEvent(MetaDetailsEvent.OnSeasonShortPress(2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedSeason)
        assertEquals("https://youtube.com/watch?v=series", state.trailerExternalUrl)
        assertEquals(null, state.pendingExternalTrailerUrl)
        assertFalse(state.isTrailerPlaying)
        assertFalse(state.showTrailerControls)
        assertFalse(state.hideLogoDuringTrailer)
        assertFalse(state.selectedSeasonHasPlayableTrailerMedia)
        coVerify(exactly = 1) {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }
        coVerify(atLeast = 1) {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        }
    }

    @Test
    fun `mark season watched reflects watched state immediately before repository flow catches up`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>(relaxed = true)
        val progressFlow = MutableStateFlow<Map<Pair<Int, Int>, com.nexio.tv.domain.model.WatchProgress>>(emptyMap())
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { watchProgressRepository.getAllEpisodeProgress(any()) } returns progressFlow
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbMetadataService.fetchSeasonEpisodes(42, 1, null) } returns listOf(
            com.nexio.tv.data.remote.api.TmdbEpisode(
                episodeNumber = 1,
                airDate = "2020-01-01"
            )
        )
        coEvery { tmdbMetadataService.fetchEpisodeEnrichment("42", any(), "eng") } returns emptyMap()

        val viewModel = buildViewModel(
            trailerService = trailerService,
            watchProgressRepository = watchProgressRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            tmdbSettings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        )
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(1))
        advanceUntilIdle()

    }

    @Test
    fun `failed season watched mutation rolls optimistic watched state back`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>(relaxed = true)
        val progressFlow = MutableStateFlow<Map<Pair<Int, Int>, com.nexio.tv.domain.model.WatchProgress>>(emptyMap())
        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        val tmdbService = mockk<TmdbService>()
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        every { watchProgressRepository.getAllEpisodeProgress(any()) } returns progressFlow
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)
        coEvery { watchProgressRepository.markAsCompletedBatch(any(), any(), any()) } throws IllegalStateException("boom")
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns "42"
        coEvery { tmdbMetadataService.fetchSeasonEpisodes(42, 1, null) } returns listOf(
            com.nexio.tv.data.remote.api.TmdbEpisode(
                episodeNumber = 1,
                airDate = "2020-01-01"
            )
        )
        coEvery { tmdbMetadataService.fetchEpisodeEnrichment("42", any(), "eng") } returns emptyMap()

        val viewModel = buildViewModel(
            trailerService = trailerService,
            watchProgressRepository = watchProgressRepository,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService
        )
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(1))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.watchedEpisodes.contains(1 to 1))
        assertFalse(viewModel.isSeasonFullyWatched(1))
    }

    @Test
    fun `failed season recap attempt clears active playback chrome through the event flow`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns TrailerResolutionResult.Playback(
            TrailerPlaybackSource(
                videoUrl = "https://example.com/series.m3u8",
                audioUrl = "https://example.com/series-audio.m4a"
            )
        )
        coEvery {
            trailerService.getSeasonRecapPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns null

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isTrailerPlaying)

        viewModel.onEvent(MetaDetailsEvent.OnPlaySeasonRecap(2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedSeason)
        assertEquals("https://example.com/series.m3u8", state.trailerUrl)
        assertFalse(state.isTrailerPlaying)
        assertFalse(state.showTrailerControls)
        assertFalse(state.hideLogoDuringTrailer)
        assertFalse(state.selectedSeasonHasPlayableRecap)
    }

    @Test
    fun `failed season recap attempt preserves external cta without reviving pending launch`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns TrailerResolutionResult.External("https://youtube.com/watch?v=series")
        coEvery {
            trailerService.getSeasonRecapPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns null

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        advanceUntilIdle()
        assertEquals(
            "https://youtube.com/watch?v=series",
            viewModel.uiState.value.pendingExternalTrailerUrl
        )

        viewModel.onEvent(MetaDetailsEvent.OnPlaySeasonRecap(2))
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(2, state.selectedSeason)
        assertEquals("https://youtube.com/watch?v=series", state.trailerExternalUrl)
        assertEquals(null, state.pendingExternalTrailerUrl)
        assertFalse(state.isTrailerPlaying)
        assertFalse(state.showTrailerControls)
        assertFalse(state.hideLogoDuringTrailer)
        assertFalse(state.selectedSeasonHasPlayableRecap)
    }

    @Test
    fun `detail trailer button enters resolving takeover state immediately before trailer resolves`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        val trailerResolutionGate = CompletableDeferred<TrailerResolutionResult?>()

        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } coAnswers {
            trailerResolutionGate.await()
        }

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        runCurrent()

        val resolvingState = viewModel.uiState.value
        assertTrue(resolvingState.isTrailerLoading)
        assertEquals(TrailerResolutionStatus.RESOLVING, resolvingState.trailerResolutionStatus)
        assertFalse(resolvingState.isTrailerPlaying)
        assertEquals(null, resolvingState.trailerUrl)

        trailerResolutionGate.complete(
            TrailerResolutionResult.Playback(
                TrailerPlaybackSource(
                    videoUrl = "https://example.com/trailer.m3u8",
                    audioUrl = "https://example.com/trailer-audio.m4a"
                )
            )
        )
        advanceUntilIdle()

        val resolvedState = viewModel.uiState.value
        assertFalse(resolvedState.isTrailerLoading)
        assertTrue(resolvedState.isTrailerPlaying)
        assertEquals("https://example.com/trailer.m3u8", resolvedState.trailerUrl)
    }

    @Test
    fun `season trailer enters resolving takeover state immediately before availability resolves`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        val seasonAvailabilityGate = CompletableDeferred<SeasonMediaAvailability>()

        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns false
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } coAnswers {
            val season = thirdArg<Int?>()
            if (season == 2) {
                seasonAvailabilityGate.await()
            } else {
                SeasonMediaAvailability(
                    hasTrailerOrTeaser = false,
                    hasRecap = false
                )
            }
        }
        coEvery {
            trailerService.getSeasonTrailerPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns TrailerPlaybackSource(
            videoUrl = "https://example.com/season-trailer.m3u8",
            audioUrl = "https://example.com/season-trailer-audio.m4a"
        )

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnSeasonShortPress(2))
        runCurrent()

        val resolvingState = viewModel.uiState.value
        assertEquals(2, resolvingState.selectedSeason)
        assertTrue(resolvingState.isTrailerLoading)
        assertEquals(TrailerResolutionStatus.RESOLVING, resolvingState.trailerResolutionStatus)
        assertFalse(resolvingState.isTrailerPlaying)
        assertEquals(null, resolvingState.trailerUrl)

        seasonAvailabilityGate.complete(
            SeasonMediaAvailability(
                hasTrailerOrTeaser = true,
                hasRecap = false
            )
        )
        advanceUntilIdle()

        val resolvedState = viewModel.uiState.value
        assertFalse(resolvedState.isTrailerLoading)
        assertTrue(resolvedState.isTrailerPlaying)
        assertEquals("https://example.com/season-trailer.m3u8", resolvedState.trailerUrl)
    }

    @Test
    fun `season recap enters resolving takeover state immediately before availability resolves`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        val seasonAvailabilityGate = CompletableDeferred<SeasonMediaAvailability>()

        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns false
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } coAnswers {
            val season = thirdArg<Int?>()
            if (season == 2) {
                seasonAvailabilityGate.await()
            } else {
                SeasonMediaAvailability(
                    hasTrailerOrTeaser = false,
                    hasRecap = false
                )
            }
        }
        coEvery {
            trailerService.getSeasonRecapPlaybackSource(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        } returns TrailerPlaybackSource(
            videoUrl = "https://example.com/season-recap.m3u8",
            audioUrl = "https://example.com/season-recap-audio.m4a"
        )

        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()

        viewModel.onEvent(MetaDetailsEvent.OnPlaySeasonRecap(2))
        runCurrent()

        val resolvingState = viewModel.uiState.value
        assertEquals(2, resolvingState.selectedSeason)
        assertTrue(resolvingState.isTrailerLoading)
        assertEquals(TrailerResolutionStatus.RESOLVING, resolvingState.trailerResolutionStatus)
        assertFalse(resolvingState.isTrailerPlaying)
        assertEquals(null, resolvingState.trailerUrl)

        seasonAvailabilityGate.complete(
            SeasonMediaAvailability(
                hasTrailerOrTeaser = false,
                hasRecap = true
            )
        )
        advanceUntilIdle()

        val resolvedState = viewModel.uiState.value
        assertFalse(resolvedState.isTrailerLoading)
        assertTrue(resolvedState.isTrailerPlaying)
        assertEquals("https://example.com/season-recap.m3u8", resolvedState.trailerUrl)
    }

    @Test
    fun `title trailer resolves on demand instead of eager loading when metadata already says trailer exists`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = false,
            hasRecap = false
        )
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns TrailerResolutionResult.Playback(
            TrailerPlaybackSource(
                videoUrl = "https://example.com/title.m3u8",
                audioUrl = "https://example.com/title-audio.m4a"
            )
        )

        val viewModel = buildViewModel(
            trailerService = trailerService,
            meta = buildSeriesMeta(trailerYtIds = listOf("titleTrailerId"))
        )
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.trailerUrl)
        assertTrue(viewModel.uiState.value.titleHasPlayableTrailerMedia)
        coVerify(exactly = 0) {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }

        viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://example.com/title.m3u8", state.trailerUrl)
        assertEquals("https://example.com/title-audio.m4a", state.trailerAudioUrl)
        assertTrue(state.isTrailerPlaying)
        assertTrue(state.showTrailerControls)
        assertTrue(state.hideLogoDuringTrailer)
        coVerify(exactly = 1) {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }
    }

    @Test
    fun `opening season options derives trailer and recap availability from metadata without resolving playback sources`() = runTest(dispatcher) {
        val trailerService = mockk<TrailerService>()
        coEvery {
            trailerService.getTitleMediaAvailability(
                tmdbId = any(),
                type = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns false
        coEvery {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        } returns null
        coEvery {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any()
            )
        } returns SeasonMediaAvailability(
            hasTrailerOrTeaser = true,
            hasRecap = true
        )
        val viewModel = buildViewModel(trailerService)
        advanceUntilIdle()
        viewModel.onEvent(MetaDetailsEvent.OnSeasonOptionsOpened(2))
        advanceUntilIdle()

        val availability = viewModel.uiState.value.seasonMediaAvailabilityBySeason[2]
        assertTrue(availability?.hasTrailerOrTeaser == true)
        assertTrue(availability?.hasRecap == true)
        coVerify(atLeast = 1) {
            trailerService.getSeasonMediaAvailability(
                tmdbId = any(),
                type = any(),
                seasonNumber = 2,
                contentId = any()
            )
        }
        coVerify(exactly = 0) {
            trailerService.resolveTrailer(
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }
    }

    private fun buildViewModel(
        trailerService: TrailerService,
        meta: Meta = buildSeriesMeta(),
        tmdbService: TmdbService? = null,
        tmdbMetadataService: TmdbMetadataService? = null,
        tmdbSettings: TmdbSettings = TmdbSettings(),
        installedAddons: List<com.nexio.tv.domain.model.Addon> = emptyList(),
        watchProgressRepository: WatchProgressRepository? = null
    ): MetaDetailsViewModel {
        val metaRepository = mockk<MetaRepository>()
        every {
            metaRepository.getMetaFromAllAddons(
                type = any(),
                id = any(),
                cacheOnDisk = any(),
                writeToDisk = any(),
                origin = any()
            )
        } returns flowOf(NetworkResult.Success(meta))

        val libraryRepository = mockk<LibraryRepository>(relaxed = true)
        every { libraryRepository.sourceMode } returns flowOf(LibrarySourceMode.LOCAL)
        every { libraryRepository.listTabs } returns flowOf(emptyList())
        every { libraryRepository.isInLibrary(any(), any()) } returns flowOf(false)
        every { libraryRepository.isInWatchlist(any(), any()) } returns flowOf(false)

        val effectiveWatchProgressRepository = watchProgressRepository ?: mockk<WatchProgressRepository>(relaxed = true).also {
            every { it.getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
            every { it.getProgress(any()) } returns flowOf(null)
        }

        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferenceDataStore.detailPageTrailerButtonEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.preferExternalMetaAddonDetail } returns flowOf(false)
        every { layoutPreferenceDataStore.blurUnwatchedEpisodes } returns flowOf(false)

        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        every { tmdbSettingsDataStore.settings } returns flowOf(tmdbSettings)

        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(false)
        every { traktAuthDataStore.state } returns flowOf(TraktAuthState())

        val resolvedTmdbService = tmdbService ?: mockk<TmdbService>().also {
            coEvery { it.tmdbToImdb(any(), any()) } returns null
            coEvery { it.ensureTmdbId(any(), any()) } returns null
        }

        val resolvedTmdbMetadataService = tmdbMetadataService ?: mockk<TmdbMetadataService>(relaxed = true)
        val tvdbSettingsDataStore = mockk<TvdbSettingsDataStore>()
        every { tvdbSettingsDataStore.settings } returns flowOf(TvdbSettings())
        val tvMetadataRouter = TvMetadataRouter(
            tvdbSettingsDataStore = tvdbSettingsDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tvdbIdentityService = mockk<TvdbIdentityService>(relaxed = true),
            tvdbMetadataService = mockk<TvdbMetadataService>(relaxed = true),
            tmdbService = resolvedTmdbService,
            tmdbMetadataService = resolvedTmdbMetadataService,
            credentialHealth = mockk(relaxed = true) { coEvery { canCallTvdb() } returns true },
            diagnosticsRecorder = mockk(relaxUnitFun = true)
        )
        val episodeRatingsSelectionRepository = mockk<EpisodeRatingsSelectionRepository>(relaxed = true)
        val mdbListRepository = mockk<MDBListRepository>(relaxed = true)
        val traktScrobbleService = mockk<TrackingScrobbleService>(relaxed = true)
        val addonRepository = mockk<AddonRepository>()
        val context = mockk<Context>(relaxed = true)
        val playerSettingsDataStore = mockk<PlayerSettingsDataStore>()
        val profileBoundary = mockk<ProfileBoundary>()
        val titleRatingOverrideRepository = mockk<com.nexio.tv.data.repository.TitleRatingOverrideRepository>()
        every { playerSettingsDataStore.playerSettings } returns flowOf(PlayerSettings())
        every { addonRepository.getInstalledAddons() } returns flowOf(installedAddons)
        every { profileBoundary.currentLanguageTag() } returns "en"
        coEvery { titleRatingOverrideRepository.enrichMeta(any(), any(), any()) } answers { firstArg() }

        return MetaDetailsViewModel(
            context = context,
            metaRepository = metaRepository,
            traktAuthDataStore = traktAuthDataStore,
            reviewsRepository = mockk<ReviewsRepository>(relaxed = true),
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            tmdbService = resolvedTmdbService,
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            metadataSecondaryRepository = MetadataSecondaryRepository(
                tmdbMetadataService = resolvedTmdbMetadataService,
                kitsuMetadataService = mockk(relaxed = true)
            ),
            profileBoundary = profileBoundary,
            mdbListRepository = mdbListRepository,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            episodeRatingsSelectionRepository = episodeRatingsSelectionRepository,
            libraryRepository = libraryRepository,
            watchProgressRepository = effectiveWatchProgressRepository,
            continueWatchingSnapshotService = mockk(relaxed = true),
            addonRepository = addonRepository,
            trackingScrobbleService = traktScrobbleService,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            playerSettingsDataStore = playerSettingsDataStore,
            trailerService = trailerService,
            savedStateHandle = SavedStateHandle(
                mapOf(
                    "itemId" to meta.id,
                    "itemType" to "series"
                )
            )
        )
    }

    private fun buildSeriesMeta(trailerYtIds: List<String> = emptyList()): Meta {
        return Meta(
            id = "show",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Shrinking",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = listOf(
                Video(
                    id = "show:1:1",
                    title = "Episode 1",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                ),
                Video(
                    id = "show:2:1",
                    title = "Episode 1",
                    released = null,
                    thumbnail = null,
                    season = 2,
                    episode = 1,
                    overview = null
                )
            ),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
            ,
            trailerYtIds = trailerYtIds
        )
    }
}
