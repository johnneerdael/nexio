package com.nexio.tv.ui.screens.detail

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.data.local.ImdbSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.TraktAuthDataStore
import com.nexio.tv.data.local.TraktAuthState
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.repository.EpisodeRatingsSelectionRepository
import com.nexio.tv.data.repository.MDBListRepository
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ImdbSettings
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
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
        meta: Meta = buildSeriesMeta()
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

        val watchProgressRepository = mockk<WatchProgressRepository>(relaxed = true)
        every { watchProgressRepository.getAllEpisodeProgress(any()) } returns flowOf(emptyMap())
        every { watchProgressRepository.getProgress(any()) } returns flowOf(null)

        val layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>()
        every { layoutPreferenceDataStore.detailPageTrailerButtonEnabled } returns flowOf(true)
        every { layoutPreferenceDataStore.preferExternalMetaAddonDetail } returns flowOf(false)
        every { layoutPreferenceDataStore.hideUnreleasedContent } returns flowOf(false)
        every { layoutPreferenceDataStore.blurUnwatchedEpisodes } returns flowOf(false)

        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings())

        val imdbSettingsDataStore = mockk<ImdbSettingsDataStore>()
        every { imdbSettingsDataStore.settings } returns flowOf(ImdbSettings())

        val traktAuthDataStore = mockk<TraktAuthDataStore>()
        every { traktAuthDataStore.isEffectivelyAuthenticated } returns flowOf(false)
        every { traktAuthDataStore.state } returns flowOf(TraktAuthState())

        val tmdbService = mockk<TmdbService>()
        coEvery { tmdbService.tmdbToImdb(any(), any()) } returns null
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns null

        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        val episodeRatingsSelectionRepository = mockk<EpisodeRatingsSelectionRepository>(relaxed = true)
        val mdbListRepository = mockk<MDBListRepository>(relaxed = true)
        val traktApi = mockk<TraktApi>(relaxed = true)
        val traktAuthService = mockk<TraktAuthService>(relaxed = true)
        val traktScrobbleService = mockk<TraktScrobbleService>(relaxed = true)
        val context = mockk<Context>(relaxed = true)

        return MetaDetailsViewModel(
            context = context,
            metaRepository = metaRepository,
            traktApi = traktApi,
            traktAuthService = traktAuthService,
            traktAuthDataStore = traktAuthDataStore,
            tmdbSettingsDataStore = tmdbSettingsDataStore,
            imdbSettingsDataStore = imdbSettingsDataStore,
            tmdbService = tmdbService,
            tmdbMetadataService = tmdbMetadataService,
            mdbListRepository = mdbListRepository,
            episodeRatingsSelectionRepository = episodeRatingsSelectionRepository,
            libraryRepository = libraryRepository,
            watchProgressRepository = watchProgressRepository,
            traktScrobbleService = traktScrobbleService,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
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
