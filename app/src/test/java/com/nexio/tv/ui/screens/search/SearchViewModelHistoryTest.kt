package com.nexio.tv.ui.screens.search

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonParserPreset
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import com.nexio.tv.testutil.layoutPreferenceDataStoreForTest
import com.nexio.tv.testutil.playerSettingsDataStoreForTest
import com.nexio.tv.testutil.searchHistoryDataStoreForTest
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
            searchHistoryDataStore = historyStore
        )

        viewModel.onEvent(SearchEvent.QueryChanged("  Severance  "))
        viewModel.onEvent(SearchEvent.SubmitSearch)
        testScheduler.advanceUntilIdle()

        val recentSearches = withTimeout(5_000) {
            historyStore.recentSearches.first { it == listOf("Severance") }
        }

        assertEquals(listOf("Severance"), recentSearches)
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

}
