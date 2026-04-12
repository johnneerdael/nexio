package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeViewModelContinueWatchingTest {

    @Test
    fun `localized episode description uses matching in progress episode overview`() = runTest {
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        coEvery {
            tmdbMetadataService.fetchEpisodeEnrichment("1399", listOf(2))
        } returns mapOf(
            (2 to 5) to episodeEnrichment("Nederlandse aflevering")
        )
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt0944947",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt0944947:2:5",
                season = 2,
                episode = 5,
                episodeTitle = "The Ghost of Harrenhal",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            ),
            episodeDescription = "English episode"
        )

        val description = localizedContinueWatchingEpisodeDescription(
            tmdbMetadataService = tmdbMetadataService,
            tmdbId = "1399",
            item = item
        )

        assertEquals("Nederlandse aflevering", description)
        coVerify(exactly = 1) {
            tmdbMetadataService.fetchEpisodeEnrichment("1399", listOf(2))
        }
    }

    @Test
    fun `localized episode description uses matching next up episode overview`() = runTest {
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        coEvery {
            tmdbMetadataService.fetchEpisodeEnrichment("1399", listOf(3))
        } returns mapOf(
            (3 to 1) to episodeEnrichment("Nederlandse volgende aflevering")
        )
        val item = ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt0944947",
                contentType = "series",
                name = "Game of Thrones",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt0944947:3:1",
                season = 3,
                episode = 1,
                episodeTitle = "Valar Dohaeris",
                episodeDescription = "English next episode",
                thumbnail = null,
                lastWatched = 42L
            )
        )

        val description = localizedContinueWatchingEpisodeDescription(
            tmdbMetadataService = tmdbMetadataService,
            tmdbId = "1399",
            item = item
        )

        assertEquals("Nederlandse volgende aflevering", description)
        coVerify(exactly = 1) {
            tmdbMetadataService.fetchEpisodeEnrichment("1399", listOf(3))
        }
    }

    @Test
    fun `localized episode description skips non episodic items`() = runTest {
        val tmdbMetadataService = mockk<TmdbMetadataService>()
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt123",
                contentType = "movie",
                name = "Movie",
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "tt123",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )

        val description = localizedContinueWatchingEpisodeDescription(
            tmdbMetadataService = tmdbMetadataService,
            tmdbId = "123",
            item = item
        )

        assertNull(description)
        coVerify(exactly = 0) {
            tmdbMetadataService.fetchEpisodeEnrichment(any(), any())
        }
    }

    private fun episodeEnrichment(overview: String?): TmdbEpisodeEnrichment {
        return TmdbEpisodeEnrichment(
            tmdbEpisodeId = null,
            voteAverage = null,
            title = null,
            overview = overview,
            thumbnail = null,
            airDate = null,
            runtimeMinutes = null
        )
    }
}
