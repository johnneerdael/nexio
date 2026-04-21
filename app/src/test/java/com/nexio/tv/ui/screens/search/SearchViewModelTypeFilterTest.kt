package com.nexio.tv.ui.screens.search

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.ImdbPosterLookupService
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.remote.api.ImdbSearchService
import com.nexio.tv.data.remote.api.ImdbSuggestion
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogExtra
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import com.nexio.tv.testutil.playerSettingsDataStoreForTest
import com.nexio.tv.testutil.searchHistoryDataStoreForTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTypeFilterTest {
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
    fun `typing suggestions exclude channel and tv catalogs`() = runTest(dispatcher) {
        val movieCatalog = searchableCatalog("mov", "Top Movies", ContentType.MOVIE)
        val seriesCatalog = searchableCatalog("ser", "Top Series", ContentType.SERIES)
        val channelCatalog = searchableCatalog("chan", "Channels", ContentType.CHANNEL)
        val tvCatalog = searchableCatalog("tv", "Live TV", ContentType.TV)

        val addon = testAddon(
            id = "addon-1",
            catalogs = listOf(movieCatalog, seriesCatalog, channelCatalog, tvCatalog)
        )

        val typesRequested = mutableListOf<String>()
        val repo = RecordingCatalogRepository(
            onQuery = { type -> typesRequested += type },
            itemsForType = { type ->
                when (type) {
                    "movie" -> listOf(metaPreview(id = "m1", name = "Bourne Identity", type = ContentType.MOVIE))
                    "series" -> listOf(metaPreview(id = "s1", name = "Bourne Supremacy Show", type = ContentType.SERIES))
                    "channel" -> listOf(metaPreview(id = "c1", name = "BBC News", type = ContentType.CHANNEL))
                    "tv" -> listOf(metaPreview(id = "t1", name = "Bourne Live Channel", type = ContentType.TV))
                    else -> emptyList()
                }
            }
        )

        val viewModel = buildViewModel(addon = addon, catalogRepository = repo)
        viewModel.onEvent(SearchEvent.QueryChanged("Bourne"))
        dispatcher.scheduler.advanceUntilIdle()

        val suggestions = withTimeout(5_000) {
            viewModel.uiState.first { it.suggestions.isNotEmpty() || typesRequested.size >= 2 }.suggestions
        }

        assertTrue(
            "Channel catalog must not be queried; requested types=$typesRequested",
            typesRequested.none { it == "channel" || it == "tv" }
        )
        assertTrue("Movie catalog should be queried", typesRequested.contains("movie"))
        assertTrue("Series catalog should be queried", typesRequested.contains("series"))

        assertFalse(
            "Channel item must not appear in suggestions: $suggestions",
            suggestions.any { it.contains("BBC News", ignoreCase = true) }
        )
        assertFalse(
            "Live TV item must not appear in suggestions: $suggestions",
            suggestions.any { it.contains("Live Channel", ignoreCase = true) }
        )
        assertTrue(
            "Movie suggestion should appear: $suggestions",
            suggestions.any { it == "Bourne Identity" }
        )
    }

    @Test
    fun `non-media items inside movie catalog response are filtered out`() = runTest(dispatcher) {
        val movieCatalog = searchableCatalog("mov", "Top Movies", ContentType.MOVIE)
        val addon = testAddon("addon-1", catalogs = listOf(movieCatalog))

        val repo = RecordingCatalogRepository(
            onQuery = {},
            itemsForType = { _ ->
                listOf(
                    metaPreview(id = "m1", name = "Real Movie", type = ContentType.MOVIE),
                    metaPreview(id = "c1", name = "Leaked Channel", type = ContentType.CHANNEL),
                    metaPreview(id = "u1", name = "Leaked Unknown", type = ContentType.UNKNOWN)
                )
            }
        )

        val viewModel = buildViewModel(addon = addon, catalogRepository = repo)
        viewModel.onEvent(SearchEvent.QueryChanged("Leak"))
        dispatcher.scheduler.advanceUntilIdle()

        val suggestions = withTimeout(5_000) {
            viewModel.uiState.first { it.suggestions.isNotEmpty() || it.query == "Leak" }.suggestions
        }

        assertFalse("Channel item leaked into suggestions: $suggestions", suggestions.contains("Leaked Channel"))
        assertFalse("Unknown item leaked into suggestions: $suggestions", suggestions.contains("Leaked Unknown"))
    }

    @Test
    fun `submitted search drops non-media items from catalog rows`() = runTest(dispatcher) {
        val movieCatalog = searchableCatalog("mov", "Top Movies", ContentType.MOVIE)
        val addon = testAddon("addon-1", catalogs = listOf(movieCatalog))

        val repo = RecordingCatalogRepository(
            onQuery = {},
            itemsForType = { _ ->
                listOf(
                    metaPreview(id = "m1", name = "Real Movie", type = ContentType.MOVIE),
                    metaPreview(id = "c1", name = "Leaked Channel", type = ContentType.CHANNEL)
                )
            }
        )

        val viewModel = buildViewModel(addon = addon, catalogRepository = repo)
        viewModel.onEvent(SearchEvent.QueryChanged("Real"))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        dispatcher.scheduler.advanceUntilIdle()

        val rows = withTimeout(5_000) {
            viewModel.uiState.first { it.catalogRows.isNotEmpty() && !it.isSearching }.catalogRows
        }

        val names = rows.flatMap { it.items.map { item -> item.name } }
        assertEquals(listOf("Real Movie"), names)
    }

    private fun TestScope.buildViewModel(
        addon: Addon,
        catalogRepository: CatalogRepository
    ): SearchViewModel {
        return SearchViewModel(
            addonRepository = SingleAddonRepository(addon),
            catalogRepository = catalogRepository,
            layoutPreferenceDataStore = layoutPreferenceDataStoreForTest(),
            playerSettingsDataStore = playerSettingsDataStoreForTest(),
            searchHistoryDataStore = searchHistoryDataStoreForTest(),
            imdbSearchService = EmptyImdbSearchService,
            imdbPosterLookupService = mockk<ImdbPosterLookupService>().apply {
                coEvery { lookupPosterUrl(any(), any(), any()) } returns null
            },
            debugSettingsDataStore = mockk<DebugSettingsDataStore>().apply {
                every { searchPosterPreviewEnabled } returns flowOf(false)
            }
        )
    }

    private object EmptyImdbSearchService : ImdbSearchService {
        override suspend fun search(query: String, types: Set<String>): List<ImdbSuggestion> = emptyList()
    }

    private fun searchableCatalog(id: String, name: String, type: ContentType): CatalogDescriptor =
        CatalogDescriptor(
            type = type,
            id = id,
            name = name,
            extra = listOf(CatalogExtra(name = "search", isRequired = true))
        )

    private fun testAddon(id: String, catalogs: List<CatalogDescriptor>): Addon = Addon(
        id = id,
        name = id,
        displayName = id,
        version = "1.0.0",
        description = null,
        logo = null,
        baseUrl = "https://example.invalid/$id",
        catalogs = catalogs,
        types = catalogs.map { it.type }.distinct(),
        resources = listOf(AddonResource(name = "catalog", types = catalogs.map { it.apiType }, idPrefixes = null)),
        parserPreset = AddonParserPreset.GENERIC
    )

    private fun metaPreview(id: String, name: String, type: ContentType): MetaPreview = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private class SingleAddonRepository(private val addon: Addon) : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(listOf(addon))
        override suspend fun getCachedInstalledAddons(): List<Addon> = listOf(addon)
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> =
            NetworkResult.Success(addon)
        override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) = Unit
        override suspend fun removeAddon(url: String) = Unit
        override suspend fun setAddonOrder(urls: List<String>) = Unit
        override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) = Unit
    }

    private class RecordingCatalogRepository(
        private val onQuery: (String) -> Unit,
        private val itemsForType: (String) -> List<MetaPreview>
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
            onQuery(type)
            val row = CatalogRow(
                addonId = addonId,
                addonName = addonName,
                addonBaseUrl = addonBaseUrl,
                catalogId = catalogId,
                catalogName = catalogName,
                type = ContentType.fromString(type),
                rawType = type,
                items = itemsForType(type),
                isLoading = false,
                hasMore = false,
                currentPage = 1,
                supportsSkip = supportsSkip,
                skipStep = skipStep
            )
            return flowOf(NetworkResult.Success(row))
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
}
