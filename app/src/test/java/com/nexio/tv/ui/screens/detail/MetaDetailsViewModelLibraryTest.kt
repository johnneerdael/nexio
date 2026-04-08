package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.LibraryRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsViewModelLibraryTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Confirms that calling toggleLibrary via OnToggleLibrary results in exactly one call to
     * `libraryRepository.toggleDefault` per interaction.
     */
    @Test
    fun `longPressAddRemoveMatchesPlusButton - both paths call toggleDefault exactly once`() =
        runTest(dispatcher) {
            val libraryRepository = mockk<LibraryRepository>(relaxed = true)
            every { libraryRepository.sourceMode } returns flowOf(LibrarySourceMode.TRAKT)
            every { libraryRepository.listTabs } returns flowOf(emptyList())
            every { libraryRepository.isInLibrary(any(), any()) } returns flowOf(false)
            every { libraryRepository.isInWatchlist(any(), any()) } returns flowOf(false)
            coEvery { libraryRepository.toggleDefault(any()) } returns Unit

            val viewModel = buildViewModel(libraryRepository)
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary)
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary)
            advanceUntilIdle()

            coVerify(exactly = 2) { libraryRepository.toggleDefault(any()) }
        }

    /**
     * Verifies a two-call sequence for a changing watchlist state: add then remove.
     */
    @Test
    fun `addThenRemoveEmitsTwoDistinctCalls - toggleDefault called once for add once for remove`() =
        runTest(dispatcher) {
            val watchlistFlow = MutableStateFlow(false)
            val libraryRepository = mockk<LibraryRepository>(relaxed = true)
            every { libraryRepository.sourceMode } returns flowOf(LibrarySourceMode.TRAKT)
            every { libraryRepository.listTabs } returns flowOf(emptyList())
            every { libraryRepository.isInLibrary(any(), any()) } returns flowOf(false)
            every { libraryRepository.isInWatchlist(any(), any()) } returns watchlistFlow
            coEvery { libraryRepository.toggleDefault(any()) } returns Unit

            val viewModel = buildViewModel(libraryRepository)
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary)
            advanceUntilIdle()

            watchlistFlow.value = true
            advanceUntilIdle()

            viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary)
            advanceUntilIdle()

            coVerify(exactly = 2) { libraryRepository.toggleDefault(any()) }
        }

    private fun buildViewModel(
        libraryRepository: LibraryRepository = defaultLibraryRepository()
    ): MetaDetailsViewModel {
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery { tmdbService.tmdbToImdb(any(), any()) } returns null
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns null

        return buildMetaDetailsViewModel(
            meta = buildMovieMeta(),
            itemType = "movie",
            tmdbService = tmdbService,
            tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true),
            watchProgressRepository = mockk(relaxed = true),
            libraryRepository = libraryRepository
        )
    }

    private fun buildMovieMeta(): Meta = Meta(
        id = "tt1234567",
        type = com.nexio.tv.domain.model.ContentType.MOVIE,
        rawType = "movie",
        name = "Test Movie",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2024",
        imdbRating = null,
        genres = emptyList(),
        runtime = null,
        director = emptyList(),
        cast = emptyList(),
        castMembers = emptyList(),
        videos = emptyList(),
        productionCompanies = emptyList(),
        networks = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList(),
        trailerYtIds = emptyList()
    )
}
