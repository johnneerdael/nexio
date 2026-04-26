package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.repository.ContinueWatchingMetadataSnapshot
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.domain.model.WatchProgress
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeViewModelContinueWatchingTest {

    @Test
    fun `localized episode description uses matching in progress episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
            (2 to 5) to episodeEnrichment("Nederlandse aflevering")
            )
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
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertEquals("Nederlandse aflevering", description)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `localized episode description uses matching next up episode overview`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        coEvery {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
            (3 to 1) to episodeEnrichment("Nederlandse volgende aflevering")
            )
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
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertEquals("Nederlandse volgende aflevering", description)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
    }

    @Test
    fun `localized episode description skips non episodic items`() = runTest {
        val tvMetadataRouter = mockk<TvMetadataRouter>()
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
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter),
            item = item
        )

        assertNull(description)
        coVerify(exactly = 0) {
            tvMetadataRouter.fetchEpisodeEnrichment(any())
        }
        coVerify(exactly = 0) {
            tmdbMetadataService.fetchEpisodeEnrichment(any(), any())
        }
    }

    @Test
    fun `continue watching playback click persists route context and click-time metadata`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val snapshotService = mockk<ContinueWatchingSnapshotService>()
        val snapshotSlot = slot<ContinueWatchingMetadataSnapshot>()
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tvdb:121361",
                contentType = "series",
                name = "Game of Thrones",
                poster = "poster",
                backdrop = "backdrop",
                logo = "logo",
                videoId = "tvdb:121361:1:1",
                season = 1,
                episode = 1,
                episodeTitle = "Winter Is Coming",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )
        every { viewModel.metadataRouterFacade } returns defaultMetadataRouterFacadeForManualConstruction()
        every { viewModel.continueWatchingSnapshotService } returns snapshotService
        coJustRun { snapshotService.recordMetadataSnapshot("series:tvdb:121361", capture(snapshotSlot)) }

        viewModel.recordContinueWatchingRouteContextForPlayback(item)

        coVerify(exactly = 1) {
            snapshotService.recordMetadataSnapshot("series:tvdb:121361", any())
        }
        assertEquals("tvdb:121361", snapshotSlot.captured.parentId)
        assertEquals("Game of Thrones", snapshotSlot.captured.clickTimeDisplayMetadata.title)
        assertEquals(ContinueWatchingMetadataSnapshot.CURRENT_ROUTING_VERSION, snapshotSlot.captured.routingVersion)
    }

    private fun episodeEnrichment(overview: String?): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            providerEpisodeId = null,
            title = null,
            overview = overview,
            thumbnail = null,
            airDate = null,
            runtimeMinutes = null
        )
    }
}
