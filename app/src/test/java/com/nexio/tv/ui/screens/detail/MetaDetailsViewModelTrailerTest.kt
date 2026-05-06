package com.nexio.tv.ui.screens.detail

import android.util.Log
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.data.integration.metadata.MetadataSecondaryRepository
import com.nexio.tv.data.trailer.SeasonMediaAvailability
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins the contract that `MetaDetailsViewModel.fetchTrailerUrl(...)` routes through
 * `MetadataRouterFacade.fetchTrailer(...)` (depth = DETAIL_MEDIA) so canonical
 * `metadata.route_decision` and `metadata.field_selected` trace events fire on
 * trailer button click, instead of bypassing the facade with a direct
 * trailer-service ownership path.
 *
 * Task 10 of the cluster-A facade-bypass migration. Pattern B (facade wrapper)
 * is required because `TrailerResolutionResult` carries player-ready playback
 * sources (combined URL or adaptive video+audio with In-App YouTube extraction)
 * that the resolver pipeline's `ResolvedField.TRAILERS` carry-set does not produce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsViewModelTrailerTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any()) } returns 0
        every { Log.i(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun buildMovieMeta(): Meta = Meta(
        id = "tt0133093",
        type = ContentType.MOVIE,
        rawType = "movie",
        name = "The Matrix",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "1999",
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
        trailerYtIds = listOf("m8e-FF8MsqU")
    )

    @Test
    fun `fetchTrailerUrl routes through MetadataRouterFacade for trace observability`() = runTest {
        val meta = buildMovieMeta()

        val trailerService = mockk<TrailerService>(relaxed = true)
        coEvery {
            trailerService.getTitleMediaAvailability(any(), any(), any(), any())
        } returns true
        coEvery {
            trailerService.getSeasonMediaAvailability(any(), any(), any(), any())
        } returns SeasonMediaAvailability()
        coEvery {
            trailerService.resolvePlaybackSource(any(), any(), any())
        } returns TrailerResolutionResult.Playback(
            source = TrailerPlaybackSource(videoUrl = "https://example.test/trailer.mp4")
        )

        val metadataSecondaryRepository = mockk<MetadataSecondaryRepository>(relaxed = true)

        val realFacade = testMetadataRouterFacade(
            providerMetadataRouter = defaultTvMetadataRouter(),
            metadataSecondaryRepository = metadataSecondaryRepository,
            trailerService = trailerService
        )
        val facade = spyk(realFacade)

        val vm = buildMetaDetailsViewModel(
            meta = meta,
            itemType = "movie",
            trailerService = trailerService,
            metadataRouterFacade = facade
        )

        advanceUntilIdle()

        // Trigger fetchTrailerUrl via OnTrailerButtonClick. titleHasPlayableTrailerMedia
        // is true (mock above), and trailerUrl/trailerExternalUrl are both blank in
        // initial state, so the button click falls through to fetchTrailerUrl().
        vm.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
        advanceUntilIdle()

        // Trailer state should now be READY with the playback URL from the mocked
        // resolver result, proving the result flowed back through the facade.
        assertEquals(
            "https://example.test/trailer.mp4",
            vm.uiState.value.trailerUrl
        )

        // Pin: VM goes through facade.fetchTrailer with DETAIL_MEDIA depth.
        coVerify(exactly = 1) {
            facade.fetchTrailer(
                metadataRequest = match { it.depth == MetadataDepth.DETAIL_MEDIA },
                title = any(),
                year = any(),
                tmdbId = any(),
                type = any(),
                seasonNumber = any(),
                contentId = any(),
                fallbackYtIds = any()
            )
        }
        coVerify(exactly = 1) {
            trailerService.resolvePlaybackSource(
                TrailerPlaybackRef.YouTubeId("m8e-FF8MsqU"),
                any(),
                any()
            )
        }
    }
}
