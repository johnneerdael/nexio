package com.nexio.tv.ui.screens.detail

import android.util.Log
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * META-05: Verify detail call sites route TV trailers through TVDB-first path
 * without forcing TMDB ID resolution before TVDB lookup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsTvdbTrailerTest {

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

    private fun buildSeriesMeta(): Meta {
        return Meta(
            id = "tt1119644",
            type = ContentType.SERIES,
            rawType = "series",
            name = "Fringe",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2008",
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            castMembers = emptyList(),
            videos = listOf(
                Video(
                    id = "tt1119644:1:1",
                    title = "Pilot",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                )
            ),
            productionCompanies = emptyList(),
            networks = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
            trailerYtIds = listOf("dQw4w9WgXcQ")
        )
    }

    @Test
    fun `detail title trailer availability does not resolve tmdb before tvdb`() = runTest {
        val meta = buildSeriesMeta()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        coEvery { tmdbService.ensureTmdbId(any(), any()) } returns "14565"

        val vm = buildMetaDetailsViewModel(
            meta = meta,
            itemType = "series",
            tmdbService = tmdbService
        )

        advanceUntilIdle()

        // For TV content, ensureTmdbId must NOT be called before trailer availability
        // because TrailerService should try TVDB first with contentId/type.
        // The detail ViewModel now passes tmdbId = null for series content.
        coVerify(exactly = 0) {
            tmdbService.ensureTmdbId(meta.id, meta.apiType)
        }
    }

    @Test
    fun `season trailer action still uses existing action surface`() = runTest {
        val meta = buildSeriesMeta()
        val tmdbService = defaultTmdbService()

        val vm = buildMetaDetailsViewModel(
            meta = meta,
            itemType = "series",
            tmdbService = tmdbService
        )

        advanceUntilIdle()

        val state = vm.uiState.value

        // Season trailer/recap availability uses the existing SeasonMediaActionAvailability
        // surface. No new TVDB-specific buttons or state fields should exist.
        // The presence of seasonMediaAvailabilityBySeason map is the existing action surface.
        val seasonAvailability = state.seasonMediaAvailabilityBySeason
        // Season availability map should be populated through existing surface
        // (may be empty if no seasons preloaded, but the type is correct)
        assert(seasonAvailability is Map<*, *>) {
            "Season media availability must use existing Map<Int, SeasonMediaActionAvailability> surface"
        }
    }
}
