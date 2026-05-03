package com.nexio.tv.ui.screens.search

import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.ImdbPosterLookupService
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.remote.api.ImdbSuggestion
import com.nexio.tv.data.repository.ImdbTitleSearchRepository
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.remote.api.TmdbMultiSearchResult
import com.nexio.tv.data.repository.TmdbDiscoveryClient
import com.nexio.tv.data.repository.TmdbDiscoveryService
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogExtra
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.TitleRatingSource
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import com.nexio.tv.testutil.playerSettingsDataStoreForTest
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.searchHistoryDataStoreForTest
import com.nexio.tv.testutil.testProfileManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTmdbTest {
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
    fun `submitted search places TMDB row before addon rows`() = runTest(dispatcher) {
        val viewModel = createViewModel(
            addonRepository = FakeAddonRepository(listOf(searchableAddon())),
            catalogRepository = FakeCatalogRepository(addonRow = addonCatalogRow()),
            tmdbDiscoveryService = FakeTmdbDiscoveryClient(
                multiSearch = listOf(tmdbMultiResult(id = 603, mediaType = "movie", title = "The Matrix"))
            ).createService()
        )

        viewModel.onEvent(SearchEvent.QueryChanged("matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.advanceUntilIdle()

        val rows = viewModel.uiState.value.catalogRows
        assertEquals(listOf("tmdb_search", "addon_search"), rows.map { it.catalogId })
        assertEquals("TMDB Search", rows[0].catalogName)
        assertEquals("Addon Search", rows[1].catalogName)
    }

    @Test
    fun `submitted search with TMDB rows and no searchable addons does not show no searchable addons error`() =
        runTest(dispatcher) {
            val viewModel = createViewModel(
                addonRepository = FakeAddonRepository(emptyList()),
                tmdbDiscoveryService = FakeTmdbDiscoveryClient(
                    multiSearch = listOf(tmdbMultiResult(id = 603, mediaType = "movie", title = "The Matrix"))
                ).createService()
            )

            viewModel.onEvent(SearchEvent.QueryChanged("matrix"))
            viewModel.onEvent(SearchEvent.SubmitSearch)
            testScheduler.advanceUntilIdle()

            val state = viewModel.uiState.value
            assertEquals(listOf("tmdb_search"), state.catalogRows.map { it.catalogId })
            assertFalse(state.isSearching)
            assertNull(state.error)
        }

    @Test
    fun `submitted search falls back to addon search when TMDB service throws`() = runTest(dispatcher) {
        val viewModel = createViewModel(
            addonRepository = FakeAddonRepository(listOf(searchableAddon())),
            catalogRepository = FakeCatalogRepository(addonRow = addonCatalogRow()),
            tmdbDiscoveryService = FakeTmdbDiscoveryClient(throwCredential = true).createService()
        )

        viewModel.onEvent(SearchEvent.QueryChanged("matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(listOf("addon_search"), state.catalogRows.map { it.catalogId })
        assertNull(state.error)
    }

    @Test
    fun `submitted search does not publish stale TMDB results after query changes`() = runTest(dispatcher) {
        val tmdbSearch = CompletableDeferred<List<TmdbMultiSearchResult>>()
        val viewModel = createViewModel(
            addonRepository = FakeAddonRepository(listOf(searchableAddon())),
            catalogRepository = FakeCatalogRepository(addonRow = addonCatalogRow()),
            tmdbDiscoveryService = FakeTmdbDiscoveryClient(multiSearchDeferred = tmdbSearch).createService()
        )

        viewModel.onEvent(SearchEvent.QueryChanged("matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.runCurrent()

        viewModel.onEvent(SearchEvent.QueryChanged("matrixx"))
        tmdbSearch.complete(listOf(tmdbMultiResult(id = 603, mediaType = "movie", title = "The Matrix")))
        testScheduler.advanceUntilIdle()

        assertEquals(emptyList<CatalogRow>(), viewModel.uiState.value.catalogRows)
    }

    @Test
    fun `submitted search publishes addon rows before delayed TMDB search completes`() = runTest(dispatcher) {
        val tmdbSearch = CompletableDeferred<List<TmdbMultiSearchResult>>()
        val viewModel = createViewModel(
            addonRepository = FakeAddonRepository(listOf(searchableAddon())),
            catalogRepository = FakeCatalogRepository(addonRow = addonCatalogRow()),
            tmdbDiscoveryService = FakeTmdbDiscoveryClient(multiSearchDeferred = tmdbSearch).createService()
        )

        viewModel.onEvent(SearchEvent.QueryChanged("matrix"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.runCurrent()

        assertEquals(listOf("addon_search"), viewModel.uiState.value.catalogRows.map { it.catalogId })

        tmdbSearch.complete(listOf(tmdbMultiResult(id = 603, mediaType = "movie", title = "The Matrix")))
        testScheduler.advanceUntilIdle()

        assertEquals(
            listOf("tmdb_search", "addon_search"),
            viewModel.uiState.value.catalogRows.map { it.catalogId }
        )
    }

    private fun TestScope.createViewModel(
        addonRepository: AddonRepository = FakeAddonRepository(emptyList()),
        catalogRepository: CatalogRepository = FakeCatalogRepository(),
        tmdbDiscoveryService: TmdbDiscoveryService = FakeTmdbDiscoveryClient().createService(),
        tmdbCatalogSettingsDataStore: TmdbCatalogSettingsDataStore = tmdbSettingsDataStoreForTest()
    ): SearchViewModel {
        return SearchViewModel(
            addonRepository = addonRepository,
            catalogRepository = catalogRepository,
            layoutPreferenceDataStore = layoutPreferenceDataStoreForTest(),
            playerSettingsDataStore = playerSettingsDataStoreForTest(),
            searchHistoryDataStore = searchHistoryDataStoreForTest(),
            imdbTitleSearchRepository = object : ImdbTitleSearchRepository {
                override suspend fun search(query: String): List<ImdbSuggestion> = emptyList()
            },
            imdbPosterLookupService = mockk<ImdbPosterLookupService>().apply {
                coEvery { lookupPosterUrl(any(), any(), any()) } returns null
            },
            debugSettingsDataStore = mockk<DebugSettingsDataStore>().apply {
                every { searchPosterPreviewEnabled } returns flowOf(false)
            },
            tmdbDiscoveryService = tmdbDiscoveryService,
            tmdbCatalogSettingsDataStore = tmdbCatalogSettingsDataStore
        )
    }

    private fun TestScope.tmdbSettingsDataStoreForTest(): TmdbCatalogSettingsDataStore {
        return TmdbCatalogSettingsDataStore(
            factory = profileDataStoreFactoryForTest(),
            profileManager = testProfileManager()
        )
    }

    private class FakeAddonRepository(private val addons: List<Addon>) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(addons)
        override suspend fun getCachedInstalledAddons(): List<Addon> = addons
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> =
            NetworkResult.Error("not implemented")
        override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) = Unit
        override suspend fun removeAddon(url: String) = Unit
        override suspend fun setAddonOrder(urls: List<String>) = Unit
        override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) = Unit
    }

    private class FakeCatalogRepository(
        private val addonRow: CatalogRow? = null
    ) : CatalogRepository {
        override fun getCatalog(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Flow<NetworkResult<CatalogRow>> {
            return addonRow?.let { flowOf(NetworkResult.Success(it)) } ?: emptyFlow()
        }

        override fun getCatalogCachedFirst(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean,
            allowNetworkRefresh: Boolean
        ): Flow<NetworkResult<CatalogRow>> = emptyFlow()

        override suspend fun refreshCatalogToDisk(
            addonBaseUrl: String,
            addonId: String,
            addonName: String,
            catalogId: String,
            catalogName: String,
            type: String,
            skip: Int,
            skipStep: Int,
            extraArgs: Map<String, String>,
            supportsSkip: Boolean
        ): Result<CatalogRow> = Result.failure(UnsupportedOperationException("not implemented"))

        override fun clearCache() = Unit
    }

    private class FakeTmdbDiscoveryClient(
        private val multiSearch: List<TmdbMultiSearchResult> = emptyList(),
        private val multiSearchDeferred: CompletableDeferred<List<TmdbMultiSearchResult>>? = null,
        private val throwCredential: Boolean = false
    ) : TmdbDiscoveryClient {
        override suspend fun credential(): MetadataProviderCredential {
            if (throwCredential) error("tmdb unavailable")
            return MetadataProviderCredential("key", source = MetadataCredentialSource.BUILT_IN)
        }

        override suspend fun searchMulti(
            query: String,
            page: Int,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMultiSearchResult> = if (page == 1) {
            multiSearchDeferred?.await() ?: multiSearch
        } else {
            emptyList()
        }

        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> = emptyList()

        override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? = null

        fun createService(): TmdbDiscoveryService = TmdbDiscoveryService(this)
    }

    private fun searchableAddon(): Addon {
        return Addon(
            id = "addon",
            name = "Addon",
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = "https://addon.example",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "addon_search",
                    name = "Addon Search",
                    extra = listOf(CatalogExtra(name = "search"))
                )
            ),
            types = listOf(ContentType.MOVIE),
            resources = listOf(AddonResource(name = "catalog", types = listOf("movie"), idPrefixes = null))
        )
    }

    private fun addonCatalogRow(): CatalogRow {
        return CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://addon.example",
            catalogId = "addon_search",
            catalogName = "Addon Search",
            type = ContentType.MOVIE,
            items = listOf(preview(id = "tt-addon", name = "Addon Result")),
            hasMore = true,
            supportsSkip = true
        )
    }

    private fun preview(id: String, name: String): MetaPreview {
        return MetaPreview(
            id = id,
            type = ContentType.MOVIE,
            name = name,
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            ratingSource = TitleRatingSource.IMDB,
            genres = emptyList()
        )
    }

    private fun tmdbMultiResult(id: Int, mediaType: String, title: String): TmdbMultiSearchResult {
        return TmdbMultiSearchResult(
            id = id,
            mediaType = mediaType,
            title = title,
            popularity = 1.0
        )
    }
}
