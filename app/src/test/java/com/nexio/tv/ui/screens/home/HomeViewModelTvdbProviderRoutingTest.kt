package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.TmdbMetadataService
import com.nexio.tv.core.tmdb.TmdbService
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeViewModelTvdbProviderRoutingTest {

    @Test
    fun `hero enrichment tvdb success does not call tmdb`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { viewModel.tvMetadataRouter } returns tvMetadataRouter
        every { viewModel.tmdbService } returns tmdbService
        every { viewModel.tmdbMetadataService } returns tmdbMetadataService
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = tvdbEnrichment()
        )

        val result = viewModel.enrichHeroItemsPipeline(
            items = listOf(seriesPreview()),
            settings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        )

        assertEquals("TVDB title", result.single().name)
        assertEquals("TVDB description", result.single().description)
        assertEquals("tvdb-backdrop", result.single().background)
        assertEquals("tvdb-logo", result.single().logo)
        assertEquals(listOf("Drama"), result.single().genres)
        assertEquals("2011", result.single().releaseInfo)
        assertEquals(8.9f, result.single().imdbRating)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = "tt0944947",
                    fallbackContentId = null,
                    contentType = ContentType.SERIES
                )
            )
        }
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `focused enrichment tvdb success does not call tmdb`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { viewModel.tvMetadataRouter } returns tvMetadataRouter
        every { viewModel.tmdbService } returns tmdbService
        every { viewModel.tmdbMetadataService } returns tmdbMetadataService
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = tvdbEnrichment()
        )

        val enrichment = viewModel.fetchProviderEnrichmentForPreview(seriesPreview())

        assertEquals("TVDB title", enrichment?.localizedTitle)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = "tt0944947",
                    fallbackContentId = null,
                    contentType = ContentType.SERIES
                )
            )
        }
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
    }

    @Test
    fun `continue watching enrichment gate allows tvdb series when tmdb disabled`() {
        val shouldEnrich = shouldEnrichContinueWatchingProviderMetadata(
            items = listOf(continueWatchingSeriesItem()),
            traktUpNextItems = emptyList(),
            settings = TmdbSettings(enabled = false, apiKey = "")
        )

        assertTrue(shouldEnrich)
    }

    @Test
    fun `continue watching enrichment gate allows tvdb next up when tmdb disabled`() {
        val shouldEnrich = shouldEnrichContinueWatchingProviderMetadata(
            items = emptyList(),
            traktUpNextItems = listOf(continueWatchingNextUpItem()),
            settings = TmdbSettings(enabled = false, apiKey = "")
        )

        assertTrue(shouldEnrich)
    }

    @Test
    fun `continue watching enrichment gate blocks movie only rows when tmdb disabled`() {
        val shouldEnrich = shouldEnrichContinueWatchingProviderMetadata(
            items = listOf(continueWatchingMovieItem()),
            traktUpNextItems = emptyList(),
            settings = TmdbSettings(enabled = false, apiKey = "")
        )

        assertFalse(shouldEnrich)
    }

    @Test
    fun `continue watching enrichment gate respects disabled basic info`() {
        val shouldEnrich = shouldEnrichContinueWatchingProviderMetadata(
            items = listOf(continueWatchingSeriesItem()),
            traktUpNextItems = emptyList(),
            settings = TmdbSettings(
                enabled = false,
                apiKey = "",
                useBasicInfo = false
            )
        )

        assertFalse(shouldEnrich)
    }

    @Test
    fun `continue watching enrichment gate preserves tmdb active behavior`() {
        val shouldEnrich = shouldEnrichContinueWatchingProviderMetadata(
            items = listOf(continueWatchingMovieItem()),
            traktUpNextItems = emptyList(),
            settings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        )

        assertTrue(shouldEnrich)
    }

    @Test
    fun `continue watching tvdb success does not call tmdb`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { viewModel.tvMetadataRouter } returns tvMetadataRouter
        every { viewModel.tmdbService } returns tmdbService
        every { viewModel.tmdbMetadataService } returns tmdbMetadataService
        val item = ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt0944947",
                contentType = "series",
                name = "Game of Thrones",
                poster = "fallback-poster",
                backdrop = "fallback-backdrop",
                logo = "fallback-logo",
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
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "TVDB title",
                description = "TVDB description",
                genres = listOf("Drama"),
                poster = "tvdb-poster",
                backdrop = "tvdb-backdrop",
                logo = "tvdb-logo",
                releaseInfo = "2011",
                rating = 8.9
            )
        )
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((2 to 5) to TvEpisodeMetadata(overview = "TVDB episode overview"))
        )

        val result = viewModel.enrichContinueWatchingItemWithProvider(
            item = item,
            settings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        ) as ContinueWatchingItem.InProgress

        assertEquals("TVDB title", result.displayMetadata?.title)
        assertEquals("TVDB description", result.displayMetadata?.description)
        assertEquals(listOf("Drama"), result.displayMetadata?.genres)
        assertEquals("tvdb-poster", result.displayMetadata?.poster)
        assertEquals("tvdb-backdrop", result.displayMetadata?.backdrop)
        assertEquals("tvdb-logo", result.displayMetadata?.logo)
        assertEquals("2011", result.displayMetadata?.releaseInfo)
        assertEquals(8.9f, result.displayMetadata?.imdbRating)
        assertEquals("TVDB episode overview", result.episodeDescription)
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
    }

    @Test
    fun `continue watching tvdb success applies metadata with tmdb disabled`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { viewModel.tvMetadataRouter } returns tvMetadataRouter
        every { viewModel.tmdbService } returns tmdbService
        every { viewModel.tmdbMetadataService } returns tmdbMetadataService
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = TvMetadataEnrichment(
                seriesTvdbId = 121361,
                localizedTitle = "TVDB title",
                description = "TVDB description",
                genres = listOf("Drama"),
                poster = "tvdb-poster",
                backdrop = "tvdb-backdrop",
                logo = "tvdb-logo",
                releaseInfo = "2011",
                rating = 8.9
            )
        )
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((2 to 5) to TvEpisodeMetadata(overview = "TVDB episode overview"))
        )

        val result = viewModel.enrichContinueWatchingItemWithProvider(
            item = continueWatchingSeriesItem(),
            settings = TmdbSettings(enabled = false, apiKey = "")
        ) as ContinueWatchingItem.InProgress

        assertEquals("TVDB title", result.displayMetadata?.title)
        assertEquals("TVDB description", result.displayMetadata?.description)
        assertEquals(listOf("Drama"), result.displayMetadata?.genres)
        assertEquals("tvdb-poster", result.displayMetadata?.poster)
        assertEquals("tvdb-backdrop", result.displayMetadata?.backdrop)
        assertEquals("tvdb-logo", result.displayMetadata?.logo)
        assertEquals("2011", result.displayMetadata?.releaseInfo)
        assertEquals(8.9f, result.displayMetadata?.imdbRating)
        assertEquals("TVDB episode overview", result.episodeDescription)
        coVerify(exactly = 1) {
            tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = "tt0944947",
                    fallbackContentId = "tt0944947:2:5",
                    contentType = ContentType.SERIES
                )
            )
        }
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEnrichment(any(), any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
    }

    @Test
    fun `runtime hydration uses tvdb episode runtime`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val metaRepository = mockk<MetaRepository>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbService = mockk<TmdbService>(relaxed = true)
        val tmdbMetadataService = mockk<TmdbMetadataService>(relaxed = true)
        every { viewModel.metaRepository } returns metaRepository
        every { viewModel.tvMetadataRouter } returns tvMetadataRouter
        every { viewModel.tmdbService } returns tmdbService
        every { viewModel.tmdbMetadataService } returns tmdbMetadataService
        every {
            metaRepository.getMetaFromAllAddons(
                type = "series",
                id = "tt0944947",
                cacheOnDisk = true,
                writeToDisk = true,
                origin = "continue_watching_runtime"
            )
        } returns flowOf(NetworkResult.Error("missing meta"))
        coEvery { tvMetadataRouter.fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf((2 to 5) to TvEpisodeMetadata(runtimeMinutes = 47))
        )
        val item = ContinueWatchingItem.NextUp(
            NextUpInfo(
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
                thumbnail = null,
                lastWatched = 42L
            )
        )

        val runtime = viewModel.resolveContinueWatchingRuntimeMinutes(item)

        assertEquals(47, runtime)
        coVerify(exactly = 1) { tvMetadataRouter.fetchEpisodeEnrichment(any()) }
        coVerify(exactly = 0) { tmdbService.ensureTmdbId(any(), any()) }
        coVerify(exactly = 0) { tmdbMetadataService.fetchEpisodeEnrichment(any(), any(), any()) }
    }

    private fun seriesPreview(): MetaPreview {
        return MetaPreview(
            id = "tt0944947",
            type = ContentType.SERIES,
            name = "Game of Thrones",
            poster = "fallback-poster",
            posterShape = PosterShape.POSTER,
            background = "fallback-backdrop",
            logo = "fallback-logo",
            description = "Fallback description",
            releaseInfo = "2010",
            imdbRating = 7.0f,
            genres = listOf("Fantasy")
        )
    }

    private fun tvdbEnrichment(): TvMetadataEnrichment {
        return TvMetadataEnrichment(
            seriesTvdbId = 121361,
            localizedTitle = "TVDB title",
            description = "TVDB description",
            genres = listOf("Drama"),
            backdrop = "tvdb-backdrop",
            logo = "tvdb-logo",
            releaseInfo = "2011",
            rating = 8.9,
            language = "en"
        )
    }

    private fun continueWatchingSeriesItem(
        contentType: String = "series"
    ): ContinueWatchingItem.InProgress {
        return ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt0944947",
                contentType = contentType,
                name = "Game of Thrones",
                poster = "fallback-poster",
                backdrop = "fallback-backdrop",
                logo = "fallback-logo",
                videoId = "tt0944947:2:5",
                season = 2,
                episode = 5,
                episodeTitle = "The Ghost of Harrenhal",
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )
    }

    private fun continueWatchingNextUpItem(
        contentType: String = "series"
    ): ContinueWatchingItem.NextUp {
        return ContinueWatchingItem.NextUp(
            NextUpInfo(
                contentId = "tt0944947",
                contentType = contentType,
                name = "Game of Thrones",
                poster = "fallback-poster",
                backdrop = "fallback-backdrop",
                logo = "fallback-logo",
                videoId = "tt0944947:2:5",
                season = 2,
                episode = 5,
                episodeTitle = "The Ghost of Harrenhal",
                thumbnail = null,
                lastWatched = 42L
            )
        )
    }

    private fun continueWatchingMovieItem(): ContinueWatchingItem.InProgress {
        return ContinueWatchingItem.InProgress(
            progress = WatchProgress(
                contentId = "tt1375666",
                contentType = "movie",
                name = "Inception",
                poster = "fallback-poster",
                backdrop = "fallback-backdrop",
                logo = "fallback-logo",
                videoId = "tt1375666",
                season = null,
                episode = null,
                episodeTitle = null,
                position = 1_000L,
                duration = 3_000L,
                lastWatched = 42L
            )
        )
    }
}
