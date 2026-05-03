package com.nexio.tv.ui.screens.detail

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
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataEnrichment
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.metadata.router.MetadataRouteFailure
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsResolveRequestRoutingTest {

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
     * Verifies that loadMeta calls metadataRouterFacade.resolveRequest() for a tvdb: series id,
     * never falls through to hydrateAddonOriginItem, and exposes displayMetadata.genres in the
     * screen state.
     */
    @Test
    fun `loadMeta for tvdb series uses resolveRequest and never calls hydrateAddonOriginItem for non-addon-origin item`() =
        runTest(dispatcher) {
            // relaxed = true so that other facade methods (fetchTvEnrichment, fetchTmdbEnrichment,
            // etc.) called from enrichMeta/applyMetaWithEnrichment don't throw.
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            val metaRepository = mockk<MetaRepository>(relaxed = true)
            val captured = slot<MetadataRequest>()

            coEvery { facade.resolveRequest(capture(captured)) } returns buildTvdbResolutionResult()
            // Stub fetchTvEnrichment so enrichMeta doesn't throw ClassCastException from relaxed mock.
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()

            val viewModel = buildMetaDetailsViewModel(
                // meta is needed for the factory — we supply a minimal placeholder.
                // The real resolution path now goes through the facade, not metaRepository.
                meta = buildMinimalSeriesMeta(),
                itemId = "tvdb:355567",
                itemType = "series",
                addonBaseUrl = null, // no addon origin → last-resort branch emits error state
                metaRepository = metaRepository,
                metadataRouterFacade = facade
            )

            advanceUntilIdle()

            // The router was called exactly once.
            coVerify(exactly = 1) { facade.resolveRequest(any<MetadataRequest>()) }

            // hydrateAddonOriginItem must NOT be called for a non-addon-origin tvdb id.
            coVerify(exactly = 0) {
                metaRepository.hydrateAddonOriginItem(
                    addon = any(),
                    type = any(),
                    id = any(),
                    cacheOnDisk = any(),
                    writeToDisk = any(),
                    origin = any()
                )
            }

            // The captured request matches the expected content id and content type.
            assertEquals("tvdb:355567", captured.captured.contentId)
            assertEquals(ContentType.SERIES, captured.captured.contentType)

            // The screen state exposes the displayMetadata fields from the resolution result.
            val state = viewModel.uiState.value
            assertFalse("isLoading should be false after loadMeta completes", state.isLoading)
            val meta = state.meta
            assertNotNull("meta should be populated", meta)
            assertEquals("Sample TVDB Title", meta?.name)
            assertEquals(listOf("Drama", "Sci-Fi"), meta?.genres)
            assertEquals("2019", meta?.releaseInfo)
        }

    /**
     * Verifies that when the router succeeds, getMeta (single-addon path) is also not called.
     */
    @Test
    fun `loadMeta with addon origin falls back to getMeta only when router yields no route`() =
        runTest(dispatcher) {
            // relaxed = true so other facade methods called from enrichMeta don't throw.
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            val metaRepository = mockk<MetaRepository>(relaxed = true)

            // Router returns a result with no route (null route → fallback path).
            coEvery { facade.resolveRequest(any()) } returns buildNoRouteResolutionResult()
            // Stub fetchTvEnrichment so enrichMeta doesn't throw ClassCastException from relaxed mock.
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns noEnrichmentDecision()

            // getMeta returns success so the fallback path resolves cleanly.
            coEvery {
                metaRepository.getMeta(
                    addonBaseUrl = any(),
                    type = any(),
                    id = any(),
                    cacheOnDisk = any(),
                    writeToDisk = any(),
                    origin = any()
                )
            } returns kotlinx.coroutines.flow.flowOf(
                com.nexio.tv.core.network.NetworkResult.Success(buildMinimalSeriesMeta())
            )

            val viewModel = buildMetaDetailsViewModel(
                meta = buildMinimalSeriesMeta(),
                itemId = "tvdb:355567",
                itemType = "series",
                addonBaseUrl = "https://addon.example.com",
                metaRepository = metaRepository,
                metadataRouterFacade = facade
            )

            advanceUntilIdle()

            // Router was still called.
            coVerify(exactly = 1) { facade.resolveRequest(any<MetadataRequest>()) }

            // getMeta was called as the last-resort fallback.
            coVerify(exactly = 1) {
                metaRepository.getMeta(
                    addonBaseUrl = "https://addon.example.com",
                    type = "series",
                    id = "tvdb:355567",
                    cacheOnDisk = any(),
                    writeToDisk = any(),
                    origin = any()
                )
            }

            // hydrateAddonOriginItem must not be called even in the fallback path.
            coVerify(exactly = 0) {
                metaRepository.hydrateAddonOriginItem(
                    addon = any(),
                    type = any(),
                    id = any(),
                    cacheOnDisk = any(),
                    writeToDisk = any(),
                    origin = any()
                )
            }

            // The screen state reflects the resolved meta from the addon fallback path.
            val state = viewModel.uiState.value
            assertFalse("isLoading should be false after fallback path completes", state.isLoading)
            assertNotNull("meta should be populated after getMeta fallback succeeds", state.meta)
        }

    /**
     * Verifies that when both the canonical router and preferredAddonBaseUrl are absent,
     * the else branch emits an error state and never calls hydrateAddonOriginItem.
     *
     * This guards the Task 3 invariant: the last-resort branch is addon-origin-only —
     * if there is no addon origin, it must emit an error state rather than fan out.
     */
    @Test
    fun `loadMeta with no canonical route and no addon origin emits error state`() =
        runTest(dispatcher) {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)
            val metaRepository = mockk<MetaRepository>(relaxed = true)
            val addonRepository = mockk<AddonRepository>(relaxed = true)

            // Force canonical to fail so the else branch is reached.
            coEvery { facade.resolveRequest(any<MetadataRequest>()) } throws
                MetadataRouteFailure.IdentityResolutionFailed(
                    "imdb:tt00000",
                    MetadataPrimaryProvider.TMDB
                )

            val viewModel = buildMetaDetailsViewModel(
                meta = buildMinimalSeriesMeta(),
                itemId = "imdb:tt00000",
                itemType = "movie",
                addonBaseUrl = null, // no preferredAddonBaseUrl → else branch always emits error
                metaRepository = metaRepository,
                metadataRouterFacade = facade,
                addonRepository = addonRepository
            )

            advanceUntilIdle()

            // hydrateAddonOriginItem must NOT be called when there is no addon origin.
            coVerify(exactly = 0) {
                metaRepository.hydrateAddonOriginItem(
                    addon = any(),
                    type = any(),
                    id = any(),
                    cacheOnDisk = any(),
                    writeToDisk = any(),
                    origin = any()
                )
            }

            // The screen state must reflect the error condition.
            val state = viewModel.uiState.value
            assertNotNull("error should be set when no addon origin is available", state.error)
            assertFalse("isLoading should be false after error state is emitted", state.isLoading)
        }

    @Test
    fun `canonical tvdb series with no addon videos populates episode videos from tvdb enrichment`() =
        runTest(dispatcher) {
            val facade = mockk<MetadataRouterFacade>(relaxed = true)

            coEvery { facade.resolveRequest(any<MetadataRequest>()) } returns buildTvdbResolutionResult()
            coEvery { facade.fetchTvEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = TvMetadataEnrichment(
                    seriesTvdbId = 355567,
                    episodeMetadata = mapOf(
                        (1 to 1) to TvEpisodeMetadata(
                            providerEpisodeId = "tvdb:9001",
                            seasonNumber = 1,
                            episodeNumber = 1,
                            title = "The Name of the Game",
                            overview = "Canonical TVDB episode overview",
                            thumbnail = "https://art.example/s1e1.jpg",
                            airDate = "2019-07-26",
                            runtimeMinutes = 61
                        )
                    )
                )
            )
            coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns TvMetadataDecision(
                provider = TvProvider.TVDB,
                reason = TvMetadataDecisionReason.TVDB_SUCCESS,
                value = emptyMap()
            )

            val viewModel = buildMetaDetailsViewModel(
                meta = buildMinimalSeriesMeta(),
                itemId = "tvdb:355567",
                itemType = "series",
                addonBaseUrl = null,
                metadataRouterFacade = facade
            )

            advanceUntilIdle()

            val videos = viewModel.uiState.value.meta?.videos.orEmpty()
            assertEquals(1, videos.size)
            assertEquals("tvdb:355567:1:1", videos.single().id)
            assertEquals("The Name of the Game", videos.single().title)
            assertEquals(1, videos.single().season)
            assertEquals(1, videos.single().episode)
            assertEquals(listOf(1), viewModel.uiState.value.seasons)
            assertEquals("The Name of the Game", viewModel.uiState.value.episodesForSeason.single().title)
        }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private fun buildTvdbResolutionResult() = MetadataResolutionResult(
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
        resolverSchedule = ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = "tvdb:355567",
            title = "Sample TVDB Title",
            overview = "Canonical overview",
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop",
            logo = null,
            rating = 8.4,
            runtimeMinutes = 55,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(
            title = "Sample TVDB Title",
            description = "Canonical overview",
            genres = listOf("Drama", "Sci-Fi"),
            releaseInfo = "2019",
            runtime = "55m",
            imdbRating = 8.4f,
            poster = "tvdb-poster",
            backdrop = "tvdb-backdrop"
        ),
        trace = emptyList()
    )

    private fun buildNoRouteResolutionResult() = MetadataResolutionResult(
        route = null,
        plan = null,
        resolverSchedule = ResolverSchedule(
            depth = MetadataDepth.DETAIL_CORE,
            localResolvers = emptyList(),
            networkResolvers = emptyList()
        ),
        resolvedDocument = ResolvedMetadataDocument(
            canonicalId = null,
            title = null,
            overview = null,
            poster = null,
            backdrop = null,
            logo = null,
            rating = null,
            runtimeMinutes = null,
            fieldOwners = emptyMap(),
            ignoredOverwrites = emptyList()
        ),
        displayMetadata = HomeDisplayMetadata(),
        trace = emptyList()
    )

    /**
     * Returns a [TvMetadataDecision] with no enrichment value — used to stub [fetchTvEnrichment]
     * so that [enrichMeta] completes without ClassCastExceptions from relaxed mockk generics.
     */
    private fun noEnrichmentDecision(): TvMetadataDecision<TvMetadataEnrichment> =
        TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_INACTIVE,
            value = null
        )

    private fun buildMinimalSeriesMeta() = Meta(
        id = "tvdb:355567",
        type = ContentType.SERIES,
        rawType = "series",
        name = "Minimal Series",
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
        videos = emptyList(),
        country = null,
        awards = null,
        language = null,
        links = emptyList()
    )
}
