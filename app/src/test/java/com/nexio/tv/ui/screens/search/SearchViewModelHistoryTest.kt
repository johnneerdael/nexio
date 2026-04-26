package com.nexio.tv.ui.screens.search

import com.nexio.tv.core.metadata.MetadataCredentialSource
import com.nexio.tv.core.metadata.MetadataProviderCredential
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.tmdb.ImdbPosterLookupService
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogPreferences
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.remote.api.ImdbSuggestion
import com.nexio.tv.data.repository.ImdbTitleSearchRepository
import com.nexio.tv.data.remote.api.TmdbMediaResult
import com.nexio.tv.data.repository.TmdbDiscoveryClient
import com.nexio.tv.data.repository.TmdbDiscoveryService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import com.nexio.tv.testutil.playerSettingsDataStoreForTest
import com.nexio.tv.testutil.profileDataStoreFactoryForTest
import com.nexio.tv.testutil.searchHistoryDataStoreForTest
import com.nexio.tv.testutil.testProfileManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelHistoryTest {
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
    fun `submit search persists recent history entry`() = runTest(dispatcher) {
        val historyStore = searchHistoryDataStoreForTest()
        historyStore.clearRecentSearches()
        val viewModel = SearchViewModel(
            addonRepository = EmptyAddonRepository(),
            catalogRepository = EmptyCatalogRepository(),
            layoutPreferenceDataStore = layoutPreferenceDataStoreForTest(),
            playerSettingsDataStore = playerSettingsDataStoreForTest(),
            searchHistoryDataStore = historyStore,
            imdbTitleSearchRepository = EmptyImdbTitleSearchRepository,
            imdbPosterLookupService = mockk<ImdbPosterLookupService>().apply {
                coEvery { lookupPosterUrl(any(), any(), any()) } returns null
            },
            debugSettingsDataStore = mockk<DebugSettingsDataStore>().apply {
                every { searchPosterPreviewEnabled } returns flowOf(false)
            },
            tmdbDiscoveryService = EmptyTmdbDiscoveryClient().createService(),
            tmdbCatalogSettingsDataStore = TmdbCatalogSettingsDataStore(
                factory = profileDataStoreFactoryForTest(),
                profileManager = testProfileManager()
            )
        )

        viewModel.onEvent(SearchEvent.QueryChanged("  Severance  "))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.advanceUntilIdle()

        val recentSearches = withTimeout(5_000) {
            historyStore.recentSearches.first { it == listOf("Severance") }
        }

        assertEquals(listOf("Severance"), recentSearches)
    }

    private object EmptyImdbTitleSearchRepository : ImdbTitleSearchRepository {
        override suspend fun search(query: String): List<ImdbSuggestion> = emptyList()
    }

    private class EmptyAddonRepository : AddonRepository {
        override fun getInstalledAddons(): Flow<List<Addon>> = flowOf(emptyList())
        override suspend fun getCachedInstalledAddons(): List<Addon> = emptyList()
        override suspend fun fetchAddon(baseUrl: String): NetworkResult<Addon> =
            NetworkResult.Error("not implemented")
        override suspend fun addAddon(url: String, parserPreset: AddonParserPreset) = Unit
        override suspend fun removeAddon(url: String) = Unit
        override suspend fun setAddonOrder(urls: List<String>) = Unit
        override suspend fun updateAddonParserPreset(url: String, parserPreset: AddonParserPreset) = Unit
    }

    private class EmptyCatalogRepository : CatalogRepository {
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
        ): Flow<NetworkResult<CatalogRow>> = emptyFlow()

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

    private class EmptyTmdbDiscoveryClient : TmdbDiscoveryClient {
        override suspend fun credential(): MetadataProviderCredential {
            return MetadataProviderCredential("key", source = MetadataCredentialSource.BUILT_IN)
        }

        override suspend fun searchMovies(
            query: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> = emptyList()

        override suspend fun searchTv(
            query: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> = emptyList()

        override suspend fun fetchCatalog(
            catalogId: String,
            preferences: TmdbCatalogPreferences
        ): List<TmdbMediaResult> = emptyList()

        override suspend fun imdbId(tmdbId: Int, contentType: ContentType): String? = null

        fun createService(): TmdbDiscoveryService = TmdbDiscoveryService(this)
    }

}
