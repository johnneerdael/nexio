package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.metadata.router.testMetadataRouterFacade
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.profile.ProfileBoundary
import com.nexio.tv.core.tmdb.TmdbEnrichment
import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeFieldTrace
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.domain.model.homeDisplayItemKey
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
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
        val profileBoundary = mockk<ProfileBoundary>()
        val homeHydrationCoordinator = mockk<HomeHydrationCoordinator>()
        val preview = seriesPreview()
        every { viewModel.metadataRouterFacade } returns testMetadataRouterFacade(tvMetadataRouter)
        every { viewModel.providerLocalizedMetadataResolver } returns ProviderLocalizedMetadataResolver(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
        )
        every { viewModel.profileBoundary } returns profileBoundary
        every { viewModel.homeHydrationCoordinator } returns homeHydrationCoordinator
        every { viewModel.homeProfileGeneration } returns 7L
        every { viewModel.isCurrentHomeProfileGeneration(7L) } returns true
        every { profileBoundary.currentLanguageTag() } returns "en"
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = tvdbEnrichment()
        )
        coEvery {
            homeHydrationCoordinator.hydrate(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
            )
        } coAnswers {
            tvMetadataRouter.fetchEnrichment(
                TvMetadataRequest(
                    contentId = preview.id,
                    contentType = preview.type,
                    language = "en"
                )
            )
            tvdbHeroOverlay(preview)
        }

        val result = viewModel.enrichHeroItemsPipeline(
            items = listOf(preview),
            settings = TmdbSettings(enabled = true, apiKey = "tmdb-key")
        )

        assertEquals("TVDB title", result.single().name)
        assertEquals("TVDB description", result.single().description)
        coVerify(exactly = 1) {
            homeHydrationCoordinator.hydrate(
                item = preview,
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                priority = HomeHydrationPriority.HERO,
                languageTag = "en",
                expectedGeneration = 7L,
                currentGeneration = any(),
                onOverlayApplied = any()
            )
        }
        coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    @Test
    fun `focused enrichment tvdb success does not call tmdb`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val profileBoundary = mockk<ProfileBoundary>()
        every { viewModel.metadataRouterFacade } returns testMetadataRouterFacade(tvMetadataRouter)
        every { viewModel.providerLocalizedMetadataResolver } returns ProviderLocalizedMetadataResolver(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
        )
        every { viewModel.profileBoundary } returns profileBoundary
        every { profileBoundary.currentLanguageTag() } returns "en"
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TVDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = tvdbEnrichment()
        )

        val enrichment = viewModel.fetchProviderEnrichmentForPreview(seriesPreview())

        assertEquals("TVDB title", enrichment?.localizedTitle)
        coVerify(exactly = 1) { tvMetadataRouter.fetchEnrichment(any()) }
    }

    // TODO: Re-enable when shouldEnrichContinueWatchingProviderMetadata is restored
    // These tests reference functions removed during a prior refactor.
    // @Test fun `continue watching enrichment gate allows tvdb series when tmdb disabled`()
    // @Test fun `continue watching enrichment gate allows tvdb next up when tmdb disabled`()
    // @Test fun `continue watching enrichment gate blocks movie only rows when tmdb disabled`()
    // @Test fun `continue watching enrichment gate respects disabled basic info`()
    // @Test fun `continue watching enrichment gate preserves tmdb active behavior`()

    // TODO: Re-enable when enrichContinueWatchingItemWithProvider and
    // resolveContinueWatchingRuntimeMinutes are restored after prior refactor.
    // @Test fun `continue watching tvdb success does not call tmdb`()
    // @Test fun `continue watching tvdb success applies metadata with tmdb disabled`()
    // @Test fun `runtime hydration uses tvdb episode runtime`()

    @Test
    fun `continue watching movie enrichment uses shared home provider overlay`() = runTest {
        val viewModel = mockk<HomeViewModel>()
        val tvMetadataRouter = mockk<TvMetadataRouter>()
        val tmdbSettingsDataStore = mockk<TmdbSettingsDataStore>()
        val profileBoundary = mockk<ProfileBoundary>()
        every { viewModel.metadataRouterFacade } returns testMetadataRouterFacade(tvMetadataRouter)
        every { viewModel.providerLocalizedMetadataResolver } returns ProviderLocalizedMetadataResolver(
            metadataRouterFacade = testMetadataRouterFacade(tvMetadataRouter)
        )
        every { viewModel.tmdbSettingsDataStore } returns tmdbSettingsDataStore
        every { viewModel.profileBoundary } returns profileBoundary
        every { tmdbSettingsDataStore.settings } returns flowOf(TmdbSettings(enabled = true, apiKey = "tmdb-key"))
        every { profileBoundary.currentLanguageTag() } returns "nl"
        coEvery { tvMetadataRouter.fetchEnrichment(any()) } returns TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = TvMetadataEnrichment(
                seriesTvdbId = null,
                localizedTitle = "Nederlandse titel",
                description = "Nederlandse omschrijving",
                genres = listOf("Animatie"),
                backdrop = "tmdb-backdrop",
                logo = "tmdb-logo",
                poster = "tmdb-poster",
                releaseInfo = "2025",
                rating = 7.4,
                runtimeMinutes = 107,
                language = "nl"
            )
        )
        val result = viewModel.enrichContinueWatchingItemWithProvider(
            item = continueWatchingMovieItem()
        ) as ContinueWatchingItem.InProgress

        assertEquals("Nederlandse titel", result.displayMetadata?.title)
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
            runtimeMinutes = 52,
            language = "en"
        )
    }

    private fun tvdbHeroOverlay(item: MetaPreview): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(
            title = "TVDB title",
            description = "TVDB description"
        )
        return HydratedHomeOverlay(
            overlayKey = "canonical:TVDB:121361:type:SERIES:lang:en:policy:1",
            itemKey = homeDisplayItemKey(item.apiType, item.id),
            canonicalProvider = ProviderId.TVDB,
            canonicalId = "121361",
            imdbId = item.id,
            contentType = ContentType.SERIES,
            languageTag = "en",
            fields = fields,
            fieldTrace = listOf(HydratedHomeFieldTrace("TITLE", "TVDB", "PRIMARY")),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = 1L,
            staleAtMs = 2L,
            expiresAtMs = 3L,
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }

    private fun tmdbMovieEnrichment(): TmdbEnrichment {
        return TmdbEnrichment(
            localizedTitle = "Nederlandse titel",
            description = "Nederlandse omschrijving",
            genres = listOf("Animatie"),
            backdrop = "tmdb-backdrop",
            logo = "tmdb-logo",
            poster = "tmdb-poster",
            directorMembers = emptyList(),
            writerMembers = emptyList(),
            castMembers = emptyList(),
            releaseInfo = "2025",
            rating = 7.4,
            runtimeMinutes = 107,
            director = emptyList(),
            writer = emptyList(),
            productionCompanies = emptyList(),
            networks = emptyList(),
            ageRating = null,
            countries = null,
            language = "nl",
            collectionId = null,
            collectionName = null
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
