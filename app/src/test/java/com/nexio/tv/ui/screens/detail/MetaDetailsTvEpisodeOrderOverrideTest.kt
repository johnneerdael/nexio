package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.artwork.ArtworkBundle
import com.nexio.tv.core.metadata.router.MetadataDecisionReason
import com.nexio.tv.core.metadata.router.MetadataMediaKind
import com.nexio.tv.core.metadata.router.MetadataPrimaryProvider
import com.nexio.tv.core.metadata.router.MetadataRequest
import com.nexio.tv.core.metadata.router.MetadataRoute
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.MetadataSourceContext
import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tvdb.TvMetadataDecision
import com.nexio.tv.core.tvdb.TvMetadataDecisionReason
import com.nexio.tv.core.tvdb.TvMetadataRequest
import com.nexio.tv.core.tvdb.TvProvider
import com.nexio.tv.data.repository.MetadataDisplayRepository
import com.nexio.tv.data.repository.TvEpisodeOrderProvider
import com.nexio.tv.data.repository.TvEpisodeOrderOverrideRepository
import com.nexio.tv.data.repository.TvEpisodeOrderResolution
import com.nexio.tv.data.repository.TvEpisodeOrderResolver
import com.nexio.tv.data.repository.normalizeTmdbTvEpisodeOrderKey
import com.nexio.tv.domain.model.ContentIdentity
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LocalizationDisplayState
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.ProviderIds
import com.nexio.tv.domain.model.ResolvedDetailDisplayDocument
import com.nexio.tv.domain.model.ResolvedDisplayFields
import com.nexio.tv.domain.model.TmdbSettings
import com.nexio.tv.domain.model.TrailerDisplayState
import com.nexio.tv.domain.model.Video
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MetaDetailsTvEpisodeOrderOverrideTest {

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
    fun `detail defaults standard tv episode enrichment to tmdb order`() = runTest(dispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val metadataDisplayRepository = mockDisplayRepository(
            resolvedDocument(providerIds = ProviderIds(tmdb = "1399", tvdb = "121361", imdb = "tt0944947"))
        )
        val request = slot<TvMetadataRequest>()
        val metadataRequest = slot<MetadataRequest>()
        coEvery {
            facade.fetchTvEpisodeEnrichment(capture(metadataRequest), capture(request))
        } returns episodeDecision()

        val viewModel = buildMetaDetailsViewModel(
            meta = seriesMeta(),
            itemId = "tmdb:1399",
            itemType = "series",
            metadataRouterFacade = facade,
            metadataDisplayRepository = metadataDisplayRepository,
            tmdbSettings = episodeSettings(),
            tvEpisodeOrderResolver = orderResolver(TvEpisodeOrderProvider.TMDB_DEFAULT)
        )

        advanceUntilIdle()

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertTrue(viewModel.uiState.value.tvEpisodeOrderToggleAvailable)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderTogglePending)
        assertEquals("tmdb:1399", metadataRequest.captured.contentId)
        assertEquals("tmdb:1399", request.captured.contentId)
        assertEquals("tmdb:1399", viewModel.uiState.value.meta?.id)
    }

    @Test
    fun `detail uses tvdb episode enrichment when manual override resolves tvdb order`() = runTest(dispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val metadataDisplayRepository = mockDisplayRepository(
            resolvedDocument(providerIds = ProviderIds(tmdb = "1399", tvdb = "121361", imdb = "tt0944947"))
        )
        val request = slot<TvMetadataRequest>()
        val metadataRequest = slot<MetadataRequest>()
        coEvery {
            facade.fetchTvEpisodeEnrichment(capture(metadataRequest), capture(request))
        } returns episodeDecision()

        val viewModel = buildMetaDetailsViewModel(
            meta = seriesMeta(),
            itemId = "tmdb:1399",
            itemType = "series",
            metadataRouterFacade = facade,
            metadataDisplayRepository = metadataDisplayRepository,
            tmdbSettings = episodeSettings(),
            tvEpisodeOrderResolver = orderResolver(
                provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
                tvdbSeriesId = "121361"
            )
        )

        advanceUntilIdle()

        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertTrue(viewModel.uiState.value.tvEpisodeOrderToggleAvailable)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderTogglePending)
        assertEquals("tvdb:121361", metadataRequest.captured.contentId)
        assertEquals("tvdb:121361", request.captured.contentId)
        assertEquals("tmdb:1399", request.captured.fallbackContentId)
        assertEquals("tmdb:1399", viewModel.uiState.value.meta?.id)
    }

    @Test
    fun `detail merges tmdb route id with tvdb only resolved identity for episode order`() = runTest(dispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val metadataDisplayRepository = mockDisplayRepository(
            resolvedDocument(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "121361",
                providerIds = ProviderIds(tvdb = "121361", imdb = "tt0944947")
            )
        )
        val request = slot<TvMetadataRequest>()
        val metadataRequest = slot<MetadataRequest>()
        coEvery {
            facade.fetchTvEpisodeEnrichment(capture(metadataRequest), capture(request))
        } returns episodeDecision()
        var resolvedTmdbTvId: String? = null
        var resolvedProviderIds: ProviderIds? = null

        val viewModel = buildMetaDetailsViewModel(
            meta = seriesMeta(id = "tmdb:1399"),
            itemId = "tmdb:1399",
            itemType = "series",
            metadataRouterFacade = facade,
            metadataDisplayRepository = metadataDisplayRepository,
            tmdbSettings = episodeSettings(),
            tvEpisodeOrderResolver = object : TvEpisodeOrderResolver {
                override suspend fun resolve(
                    tmdbTvId: String?,
                    providerIds: ProviderIds
                ): TvEpisodeOrderResolution {
                    resolvedTmdbTvId = tmdbTvId
                    resolvedProviderIds = providerIds
                    return TvEpisodeOrderResolution(
                        provider = TvEpisodeOrderProvider.TVDB_DEFAULT,
                        tmdbTvId = "tmdb:tv:${tmdbTvId?.substringAfterLast(':') ?: "1399"}",
                        tvdbSeriesId = providerIds.tvdb,
                        reason = "test"
                    )
                }
            }
        )

        advanceUntilIdle()

        assertEquals("1399", resolvedTmdbTvId)
        assertEquals("1399", resolvedProviderIds?.tmdb)
        assertEquals("121361", resolvedProviderIds?.tvdb)
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertTrue(viewModel.uiState.value.tvEpisodeOrderToggleAvailable)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderTogglePending)
        assertEquals("tvdb:121361", metadataRequest.captured.contentId)
        assertEquals("tvdb:121361", request.captured.contentId)
        assertEquals("tmdb:1399", request.captured.fallbackContentId)
        assertEquals("tmdb:1399", viewModel.uiState.value.meta?.id)
    }

    @Test
    fun `detail leaves episode order toggle unavailable until hydrated tmdb tv id exists`() = runTest(dispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val metadataDisplayRepository = mockDisplayRepository(
            resolvedDocument(
                canonicalProvider = ProviderId.TVDB,
                canonicalId = "121361",
                providerIds = ProviderIds(tvdb = "121361", imdb = "tt0944947")
            )
        )

        val viewModel = buildMetaDetailsViewModel(
            meta = seriesMeta(id = "tvdb:121361"),
            itemId = "tvdb:121361",
            itemType = "series",
            metadataRouterFacade = facade,
            metadataDisplayRepository = metadataDisplayRepository,
            tmdbSettings = episodeSettings(useEpisodes = false),
            tvEpisodeOrderResolver = orderResolver(TvEpisodeOrderProvider.TVDB_DEFAULT)
        )

        advanceUntilIdle()

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderToggleAvailable)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderTogglePending)
        coVerify(exactly = 0) { facade.fetchTvEpisodeEnrichment(any(), any()) }
    }

    @Test
    fun `toggle event persists tvdb order override and refreshes detail state`() = runTest(dispatcher) {
        val facade = mockk<MetadataRouterFacade>(relaxed = true)
        val metadataDisplayRepository = mockDisplayRepository(
            resolvedDocument(providerIds = ProviderIds(tmdb = "1399", tvdb = "121361", imdb = "tt0944947"))
        )
        coEvery { facade.fetchTvEpisodeEnrichment(any(), any()) } returns episodeDecision()
        val overrideRepository = RecordingTvEpisodeOrderOverrideRepository()

        val viewModel = buildMetaDetailsViewModel(
            meta = seriesMeta(),
            itemId = "tmdb:1399",
            itemType = "series",
            metadataRouterFacade = facade,
            metadataDisplayRepository = metadataDisplayRepository,
            tmdbSettings = episodeSettings(),
            tvEpisodeOrderResolver = repoBackedOrderResolver(overrideRepository),
            tvEpisodeOrderOverrideRepository = overrideRepository
        )

        advanceUntilIdle()

        assertEquals(TvEpisodeOrderProvider.TMDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertFalse(overrideRepository.hasOverride("1399"))

        viewModel.onEvent(MetaDetailsEvent.OnToggleTvEpisodeOrderProvider)
        advanceUntilIdle()

        assertTrue(overrideRepository.hasOverride("tmdb:tv:1399"))
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, overrideRepository.getOrder("1399"))
        assertEquals(TvEpisodeOrderProvider.TVDB_DEFAULT, viewModel.uiState.value.tvEpisodeOrderProvider)
        assertTrue(viewModel.uiState.value.tvEpisodeOrderToggleAvailable)
        assertFalse(viewModel.uiState.value.tvEpisodeOrderTogglePending)
    }

    private fun mockDisplayRepository(document: ResolvedDetailDisplayDocument): MetadataDisplayRepository {
        return mockk<MetadataDisplayRepository>().also { repository ->
            coEvery { repository.resolveDetailDisplay(any(), any()) } returns document
        }
    }

    private fun orderResolver(
        provider: TvEpisodeOrderProvider,
        tvdbSeriesId: String? = null
    ): TvEpisodeOrderResolver {
        return object : TvEpisodeOrderResolver {
            override suspend fun resolve(
                tmdbTvId: String?,
                providerIds: ProviderIds
            ): TvEpisodeOrderResolution =
                TvEpisodeOrderResolution(
                    provider = provider,
                    tmdbTvId = "tmdb:tv:${tmdbTvId?.substringAfterLast(':') ?: "1399"}",
                    tvdbSeriesId = tvdbSeriesId ?: providerIds.tvdb,
                    reason = "test"
                )
        }
    }

    private fun repoBackedOrderResolver(
        overrideRepository: TvEpisodeOrderOverrideRepository
    ): TvEpisodeOrderResolver {
        return object : TvEpisodeOrderResolver {
            override suspend fun resolve(
                tmdbTvId: String?,
                providerIds: ProviderIds
            ): TvEpisodeOrderResolution {
                val key = normalizeTmdbTvEpisodeOrderKey(tmdbTvId)
                val provider = key
                    ?.let { overrideRepository.getOrder(it) }
                    ?: TvEpisodeOrderProvider.TMDB_DEFAULT
                return TvEpisodeOrderResolution(
                    provider = provider,
                    tmdbTvId = key.orEmpty(),
                    tvdbSeriesId = providerIds.tvdb,
                    reason = "repo-backed test"
                )
            }
        }
    }

    private class RecordingTvEpisodeOrderOverrideRepository : TvEpisodeOrderOverrideRepository {
        private val overrides = linkedMapOf<String, TvEpisodeOrderProvider>()

        override suspend fun getOrder(tmdbTvId: String): TvEpisodeOrderProvider =
            overrides[normalizeTmdbTvEpisodeOrderKey(tmdbTvId)] ?: TvEpisodeOrderProvider.TMDB_DEFAULT

        override suspend fun setOrder(tmdbTvId: String, provider: TvEpisodeOrderProvider) {
            val key = normalizeTmdbTvEpisodeOrderKey(tmdbTvId) ?: return
            if (provider == TvEpisodeOrderProvider.TMDB_DEFAULT) {
                overrides.remove(key)
            } else {
                overrides[key] = provider
            }
        }

        override suspend fun clearOrder(tmdbTvId: String) {
            normalizeTmdbTvEpisodeOrderKey(tmdbTvId)?.let(overrides::remove)
        }

        override suspend fun hasOverride(tmdbTvId: String): Boolean =
            normalizeTmdbTvEpisodeOrderKey(tmdbTvId)?.let(overrides::containsKey) == true
    }

    private fun episodeDecision(): TvMetadataDecision<Map<Pair<Int, Int>, TvEpisodeMetadata>> =
        TvMetadataDecision(
            provider = TvProvider.TMDB,
            reason = TvMetadataDecisionReason.TVDB_SUCCESS,
            value = mapOf(
                (1 to 1) to TvEpisodeMetadata(
                    providerEpisodeId = "episode:1",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    title = "Hydrated Pilot"
                )
            )
        )

    private fun episodeSettings(useEpisodes: Boolean = true): TmdbSettings =
        TmdbSettings(
            enabled = true,
            apiKey = "tmdb-key",
            useArtwork = false,
            useBasicInfo = false,
            useDetails = false,
            useCredits = false,
            useProductions = false,
            useNetworks = false,
            useEpisodes = useEpisodes,
            useMoreLikeThis = false,
            useReviews = false,
            useCollections = false
        )

    private fun seriesMeta(id: String = "tmdb:1399"): Meta =
        Meta(
            id = id,
            type = ContentType.SERIES,
            rawType = "series",
            name = "Series",
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
            videos = listOf(
                Video(
                    id = "$id:1:1",
                    title = "Pilot",
                    released = null,
                    thumbnail = null,
                    season = 1,
                    episode = 1,
                    overview = null
                )
            ),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )

    private fun resolvedDocument(
        canonicalProvider: ProviderId = ProviderId.TMDB,
        canonicalId: String = "1399",
        providerIds: ProviderIds
    ): ResolvedDetailDisplayDocument =
        ResolvedDetailDisplayDocument(
            route = MetadataRoute(
                provider = when (canonicalProvider) {
                    ProviderId.TVDB -> MetadataPrimaryProvider.TVDB
                    else -> MetadataPrimaryProvider.TMDB
                },
                parentId = "${canonicalProvider.name.lowercase()}:$canonicalId",
                mediaKind = MetadataMediaKind.SERIES,
                reason = MetadataDecisionReason.ITEM_TYPE_SERIES,
                sourceContext = MetadataSourceContext(),
                targetIds = emptyMap(),
                trace = emptyList()
            ),
            identity = ContentIdentity(
                canonicalProvider = canonicalProvider,
                canonicalId = canonicalId,
                providerIds = providerIds
            ),
            fields = ResolvedDisplayFields(
                title = "Series",
                originalTitle = null,
                year = null,
                releaseDate = null,
                overview = null,
                genres = emptyList(),
                runtimeText = null
            ),
            artwork = ArtworkBundle(),
            rating = null,
            trailer = TrailerDisplayState(),
            seasons = emptyList(),
            people = null,
            reviews = emptyList(),
            recommendations = emptyList(),
            collection = emptyList(),
            sourceTrace = emptyList(),
            localization = LocalizationDisplayState(
                requestedLanguage = "en",
                selectedLanguage = "en",
                fallbackReason = null
            )
        )
}
