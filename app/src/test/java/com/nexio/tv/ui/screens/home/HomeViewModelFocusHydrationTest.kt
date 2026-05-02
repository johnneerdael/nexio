package com.nexio.tv.ui.screens.home

import android.content.Context
import com.nexio.tv.core.integration.IntegrationOwnershipService
import com.nexio.tv.core.sync.AccountSyncRefreshNotifier
import com.nexio.tv.core.metadata.router.CanonicalStableIds
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataDepth
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataResolutionResult
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.metadata.router.ResolvedMetadataDocument
import com.nexio.tv.core.metadata.router.ResolverSchedule
import com.nexio.tv.core.metadata.router.SourceRole
import com.nexio.tv.core.metadata.router.SidecarStableIds
import com.nexio.tv.core.metadata.router.SourceStableIds
import com.nexio.tv.core.metadata.router.StableIdBundle
import com.nexio.tv.core.metadata.router.StableIdResolutionTrigger
import com.nexio.tv.core.profile.ProfileModeRouter
import com.nexio.tv.core.tvdb.ProviderLocalizedMetadataResolver
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.HomeCatalogSnapshotStore
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.local.SyntheticHomeCatalogStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TitleRatingOverrideRepository
import com.nexio.tv.data.repository.TrackingProviderStateService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.FirstPaintSource
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.RailHydrationState
import com.nexio.tv.domain.model.RailSource
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.domain.repository.LibraryRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
 * Regression guard for Task 2 of the rail-preview-first hydration plan.
 *
 * Verifies that [HomeViewModel.onItemFocusPipeline] sends RAIL_PREVIEW items into the
 * canonical detail-core hydration path, skips the no-route PREVIEW boundary, and is
 * idempotent for repeated focus of the same item (via [HomeViewModel.focusedItemHydrationStates]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelFocusHydrationTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onItemFocus for rail-preview tvdb item triggers canonical MetadataRouter hydration`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        // Capture all calls into a list so we can filter by depth.
        val allCaptured = mutableListOf<MetadataRequest>()
        coEvery { facade.resolveRequest(capture(allCaptured)) } returns successResult()

        val viewModel = buildTestHomeViewModel(metadataRouterFacade = facade)
        val railPreviewItem = railPreviewMetaPreview()

        viewModel.onItemFocus(railPreviewItem)
        advanceUntilIdle()

        coVerify(exactly = 0) {
            facade.resolveRequest(match { it.depth == MetadataDepth.PREVIEW })
        }
        val canonicalCall = allCaptured.firstOrNull()
            ?: error("No canonical hydration call was captured")
        assertEquals(MetadataDepth.DETAIL_CORE, canonicalCall.depth)
        assertEquals("tvdb:355567", canonicalCall.contentId)
        assertEquals(ContentType.SERIES, canonicalCall.contentType)
        assertEquals(SourceRole.RAIL_PREVIEW, canonicalCall.sourceContext.previewSourceRole)
        coVerify(exactly = 1) { facade.resolveRequest(match { it.depth == MetadataDepth.DETAIL_CORE }) }
    }

    @Test
    fun `onItemFocus for already-canonical item does not trigger MetadataRouter at PREVIEW depth`() = runTest(testDispatcher) {
        // Use a relaxed facade so the existing enrichment path (DETAIL_CORE) can call resolveRequest
        // without crashing — we only assert that our new RAIL_PREVIEW hydration does NOT fire.
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val viewModel = buildTestHomeViewModel(metadataRouterFacade = facade)

        val addonMetaItem = MetaPreview(
            id = "tvdb:355567",
            type = ContentType.SERIES,
            name = "Sample",
            poster = "p",
            posterShape = PosterShape.POSTER,
            background = "b",
            logo = "l",
            description = "d",
            releaseInfo = "2019",
            imdbRating = 8.4f,
            genres = listOf("Drama"),
            // ADDON_META_PREVIEW: already has full preview data, no RAIL_PREVIEW router hydration needed
            firstPaintSource = FirstPaintSource.ADDON_META_PREVIEW,
            firstPaintSourceProvider = ProviderId.ADDON,
            firstPaintStableIds = ProviderIds(tvdb = "355567"),
            firstPaintRailSource = RailSource.ADDON_CATALOG,
            firstPaintSourceItemId = "tvdb:355567"
        )

        viewModel.onItemFocus(addonMetaItem)
        viewModel.onItemFocus(addonMetaItem)  // second focus — still no-op for our hydration
        advanceUntilIdle()

        coVerify(exactly = 0) { facade.resolveRequest(match { it.depth == MetadataDepth.PREVIEW }) }
    }

    @Test
    fun `onItemFocus for the same rail-preview item twice only triggers canonical hydration once`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        coEvery { facade.resolveRequest(any<MetadataRequest>()) } returns successResult()

        val viewModel = buildTestHomeViewModel(metadataRouterFacade = facade)
        val railPreviewItem = railPreviewMetaPreview()

        viewModel.onItemFocus(railPreviewItem)
        viewModel.onItemFocus(railPreviewItem)  // second focus — idempotent
        advanceUntilIdle()

        // focusedItemHydrationStates transitions PREVIEW_ONLY -> HYDRATING on first call,
        // so the second call sees a non-PREVIEW_ONLY state and skips our RAIL_PREVIEW hydration.
        coVerify(exactly = 0) { facade.resolveRequest(match { it.depth == MetadataDepth.PREVIEW }) }
        coVerify(exactly = 1) { facade.resolveRequest(match { it.depth == MetadataDepth.DETAIL_CORE }) }
    }

    @Test
    fun `hero enrichment resolves focused stable bundle and passes it to title rating enrichment`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val providerRequests = mutableListOf<MetadataRequest>()
        val bundleRequests = mutableListOf<MetadataRequest>()
        val stableIdBundle = stableIdBundle()
        val item = railPreviewMetaPreview()

        coEvery { facade.resolveRequest(capture(providerRequests)) } returns successResult()
        coEvery {
            facade.resolveStableIdBundle(
                request = capture(bundleRequests),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${item.id}"
            )
        } returns stableIdBundle
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle) } answers { firstArg() }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = facade,
            titleRatingOverrideRepository = titleRatingOverrideRepository
        )

        val enriched = viewModel.enrichHeroItemsPipeline(
            items = listOf(item.copy(type = ContentType.MOVIE, rawType = "movie")),
            settings = TmdbSettings()
        )

        assertEquals("Canonical Title", enriched.single().name)
        assertEquals(MetadataDepth.DETAIL_CORE, providerRequests.single().depth)
        assertEquals(MetadataDepth.DETAIL_CORE, bundleRequests.single().depth)
        assertEquals(SourceRole.RAIL_PREVIEW, bundleRequests.single().sourceContext.previewSourceRole)
        coVerifyOrder {
            facade.resolveRequest(any())
            facade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${item.id}"
            )
            titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle)
        }
    }

    @Test
    fun `hero enrichment falls back to legacy rating enrichment when focused stable bundle resolution fails`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val item = railPreviewMetaPreview().copy(
            type = ContentType.MOVIE,
            rawType = "movie"
        )

        coEvery { facade.resolveRequest(any()) } returns successResult()
        coEvery {
            facade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${item.id}"
            )
        } throws IllegalStateException("identity backend unavailable")
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), null) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 7.7f)
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = facade,
            titleRatingOverrideRepository = titleRatingOverrideRepository
        )

        val enriched = viewModel.enrichHeroItemsPipeline(
            items = listOf(item),
            settings = TmdbSettings()
        )

        assertEquals("Canonical Title", enriched.single().name)
        assertEquals(7.7f, enriched.single().imdbRating)
        coVerify(exactly = 1) { titleRatingOverrideRepository.enrichPreview(any(), null) }
    }

    @Test
    fun `focused enrichment flush resolves focused stable bundle and passes it to title rating enrichment`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val providerRequests = mutableListOf<MetadataRequest>()
        val bundleRequests = mutableListOf<MetadataRequest>()
        val stableIdBundle = stableIdBundle()
        val focusedItem = railPreviewMetaPreview().copy(
            type = ContentType.MOVIE,
            rawType = "movie"
        )

        coEvery { facade.resolveRequest(capture(providerRequests)) } returns successResult()
        coEvery {
            facade.resolveStableIdBundle(
                request = capture(bundleRequests),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${focusedItem.id}"
            )
        } returns stableIdBundle
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 9.9f)
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = facade,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            nonPlaybackHomeWorkAllowed = true
        )
        viewModel.catalogsMap["addon_movie_popular"] = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(focusedItem),
            hasMore = false
        )

        viewModel.onItemFocus(focusedItem)
        advanceUntilIdle()

        val updatedItem = viewModel.catalogsMap.getValue("addon_movie_popular").items.single()
        assertEquals("Canonical Title", updatedItem.name)
        assertEquals(9.9f, updatedItem.imdbRating)
        assertEquals(MetadataDepth.DETAIL_CORE, providerRequests.single().depth)
        assertEquals(MetadataDepth.DETAIL_CORE, bundleRequests.single().depth)
        assertEquals(SourceRole.RAIL_PREVIEW, bundleRequests.single().sourceContext.previewSourceRole)
        coVerifyOrder {
            facade.resolveRequest(any())
            facade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${focusedItem.id}"
            )
            titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle)
        }
    }

    @Test
    fun `focused enrichment flush resolves stable bundle once for duplicate item across rows`() = runTest(testDispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val titleRatingOverrideRepository = mockk<TitleRatingOverrideRepository>()
        val stableIdBundle = stableIdBundle()
        val focusedItem = railPreviewMetaPreview().copy(
            type = ContentType.MOVIE,
            rawType = "movie"
        )

        coEvery { facade.resolveRequest(any()) } returns successResult()
        coEvery {
            facade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${focusedItem.id}"
            )
        } returns stableIdBundle
        coEvery { titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle) } answers {
            firstArg<MetaPreview>().copy(imdbRating = 9.9f)
        }

        val viewModel = buildTestHomeViewModel(
            metadataRouterFacade = facade,
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            nonPlaybackHomeWorkAllowed = true
        )
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "popular",
            catalogName = "Popular",
            type = ContentType.MOVIE,
            items = listOf(focusedItem),
            hasMore = false
        )
        viewModel.catalogsMap["addon_movie_popular"] = row
        viewModel.catalogsMap["addon_movie_featured"] = row.copy(catalogId = "featured", catalogName = "Featured")

        viewModel.onItemFocus(focusedItem)
        advanceUntilIdle()

        assertEquals(9.9f, viewModel.catalogsMap.getValue("addon_movie_popular").items.single().imdbRating)
        assertEquals(9.9f, viewModel.catalogsMap.getValue("addon_movie_featured").items.single().imdbRating)
        coVerify(exactly = 1) {
            facade.resolveStableIdBundle(
                request = any(),
                trigger = StableIdResolutionTrigger.FOCUSED_HOME_ITEM,
                itemKey = "movie:${focusedItem.id}"
            )
        }
        coVerify(exactly = 2) { titleRatingOverrideRepository.enrichPreview(any(), stableIdBundle) }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun railPreviewMetaPreview() = MetaPreview(
        id = "tvdb:355567",
        type = ContentType.SERIES,
        name = "Sample",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = "2019",
        imdbRating = null,
        genres = emptyList(),
        firstPaintSource = FirstPaintSource.RAIL_PREVIEW,
        firstPaintSourceProvider = ProviderId.TRAKT,
        firstPaintStableIds = ProviderIds(tvdb = "355567", trakt = "1"),
        firstPaintRailSource = RailSource.BUILT_IN_TRAKT,
        firstPaintSourceItemId = "trakt:show:1"
    )

    private fun successResult() = MetadataResolutionResult(
        route = MetadataRoute(
            provider = MetadataPrimaryProvider.TVDB,
            parentId = "tvdb:355567",
            mediaKind = MetadataMediaKind.SERIES,
            reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
            sourceContext = MetadataSourceContext(),
            targetIds = mapOf(MetadataPrimaryProvider.TVDB to "tvdb:355567"),
            trace = emptyList()
        ),
        plan = null,
        resolverSchedule = ResolverSchedule(MetadataDepth.DETAIL_CORE, emptyList(), emptyList()),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tvdb:355567",
            title = "Canonical Title",
            overview = null,
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop",
            logo = null,
            rating = 8.4,
            runtimeMinutes = 55,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Canonical Title",
            genres = listOf("Drama"),
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop"
        ),
        trace = emptyList()
    )

    private fun stableIdBundle() = StableIdBundle(
        itemKey = "movie:tvdb:355567",
        itemType = ContentType.MOVIE,
        canonical = CanonicalStableIds(tmdbMovieId = "550"),
        sidecars = SidecarStableIds(imdbId = "tt0137523"),
        source = SourceStableIds(
            sourceProvider = ProviderId.TRAKT,
            sourceItemId = "trakt:show:1",
            railId = RailSource.BUILT_IN_TRAKT.name,
            observedIds = ProviderIds(tvdb = "355567", trakt = "1")
        ),
        evidence = emptyList(),
        resolvedAtMs = 1L
    )

    /**
     * Constructs a real [HomeViewModel] wired for focus-hydration tests.
     *
     * All collaborators except [metadataRouterFacade] use [mockk(relaxed = true)] so the
     * [HomeViewModel.init] block can launch its observers without crashing.
     * [ProfileManager] is set to profile 1 (the default legacy profile) so
     * [HomeViewModel.startHomeProfileSession] resolves [ProfileModeRoute.DefaultLegacyRoute].
     *
     * We use a real [ProfileModeRouter] (not mocked) so the sealed-interface [when]
     * expression in [HomeViewModel.startHomeProfileSession] matches correctly.
     *
     * [Dispatchers.Main] must be set to a test dispatcher (done in [setUp]/[tearDown]) so
     * that [viewModelScope] uses the test scheduler and [advanceUntilIdle] drains coroutines.
     */
    private fun buildTestHomeViewModel(
        metadataRouterFacade: MetadataRouterFacade,
        titleRatingOverrideRepository: TitleRatingOverrideRepository = mockk(relaxed = true),
        nonPlaybackHomeWorkAllowed: Boolean = false
    ): HomeViewModel {
        // ProviderLocalizedMetadataResolver wraps the facade under test.
        // Use a no-op TvMetadataRouter so the resolver doesn't make real network calls.
        val noOpTvRouter = mockk<com.nexio.tv.core.tvdb.TvMetadataRouter> {
            coEvery { fetchEnrichment(any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = null
            )
            coEvery { fetchEpisodeEnrichment(any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = emptyMap()
            )
            coEvery { fetchSeasonEpisodes(any(), any(), any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_INACTIVE,
                value = emptyList()
            )
        }

        val profileModeRouter = ProfileModeRouter()

        // ProfileManager with profileSwitched SharedFlow so observeProfileSwitches()
        // in init doesn't NPE when it accesses profileManager.profileSwitched.
        val profileManagerWithSwitch = mockk<com.nexio.tv.core.profile.ProfileManager>(relaxed = true) {
            every { activeProfileId } returns MutableStateFlow(1)
            every { profileSwitched } returns MutableSharedFlow(extraBufferCapacity = 1)
        }

        // accountSyncRefreshNotifier and catalogPriorityHydrationNotifier have `.events`
        // SharedFlow<Long> which is collected in viewModelScope.launch {} during init.
        // Relaxed mocks for SharedFlow emit null which causes KotlinNothingValueException,
        // so we explicitly stub events to emptyFlow() (never-emitting).
        // Stub events as a never-emitting SharedFlow so the collect {} in init doesn't
        // throw KotlinNothingValueException from a relaxed-mock null emission.
        val neverEmittingEvents = MutableSharedFlow<Long>(extraBufferCapacity = 0)
        val accountSyncRefreshNotifier = mockk<AccountSyncRefreshNotifier>(relaxed = true) {
            every { events } returns neverEmittingEvents
        }
        val catalogPriorityHydrationNotifier = mockk<com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier>(relaxed = true) {
            every { events } returns neverEmittingEvents
        }
        val playbackIdleGateState = PlaybackIdleGateState().also { gate ->
            if (!nonPlaybackHomeWorkAllowed) {
                gate.onPlayerSessionStarted()
            }
        }

        return HomeViewModel(
            addonRepository = mockk(relaxed = true),
            catalogRepository = mockk(relaxed = true),
            watchProgressRepository = mockk(relaxed = true),
            libraryRepository = mockk(relaxed = true),
            metaRepository = mockk(relaxed = true),
            layoutPreferenceDataStore = mockk(relaxed = true),
            tmdbSettingsDataStore = mockk(relaxed = true),
            tmdbCatalogSettingsDataStore = mockk(relaxed = true),
            kitsuCatalogSettingsDataStore = mockk(relaxed = true),
            traktSettingsDataStore = mockk(relaxed = true),
            mdbListSettingsDataStore = mockk(relaxed = true),
            simklSettingsDataStore = mockk(relaxed = true),
            playerSettingsDataStore = mockk(relaxed = true),
            traktDiscoverySnapshotStore = mockk(relaxed = true),
            simklDiscoverySnapshotStore = mockk(relaxed = true),
            mdbListDiscoverySnapshotStore = mockk(relaxed = true),
            continueWatchingSnapshotService = mockk(relaxed = true),
            trackingScrobbleService = mockk(relaxed = true),
            traktDiscoveryService = mockk(relaxed = true),
            simklDiscoveryService = mockk(relaxed = true),
            mdbListDiscoveryService = mockk(relaxed = true),
            tmdbDiscoveryService = mockk(relaxed = true),
            kitsuDiscoveryService = mockk(relaxed = true),
            mdbListRepository = mockk(relaxed = true),
            titleRatingOverrideRepository = titleRatingOverrideRepository,
            tmdbService = mockk(relaxed = true),
            metadataRouterFacade = metadataRouterFacade,
            providerLocalizedMetadataResolver = ProviderLocalizedMetadataResolver(metadataRouterFacade),
            trailerService = mockk(relaxed = true),
            trailerSettingsDataStore = mockk(relaxed = true),
            accountSyncRefreshNotifier = accountSyncRefreshNotifier,
            catalogPriorityHydrationNotifier = catalogPriorityHydrationNotifier,
            homeCatalogSnapshotStore = mockk(relaxed = true),
            homeCatalogRefreshCoordinator = mockk(relaxed = true),
            debugSettingsDataStore = mockk(relaxed = true),
            metadataDiskCacheStore = mockk(relaxed = true),
            syntheticHomeCatalogStore = mockk(relaxed = true),
            profileManager = profileManagerWithSwitch,
            profileModeRouter = profileModeRouter,
            profileBoundary = mockk(relaxed = true),
            trackingProviderStateService = mockk(relaxed = true),
            playbackIdleGateState = playbackIdleGateState,
            integrationOwnershipService = mockk(relaxed = true),
            appContext = mockk<Context>(relaxed = true)
        )
    }
}
