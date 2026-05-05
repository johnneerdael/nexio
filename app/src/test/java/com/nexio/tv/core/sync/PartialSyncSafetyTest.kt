package com.nexio.tv.core.sync

import com.google.gson.Gson
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.ui.screens.home.order.DefaultSortKey
import com.nexio.tv.ui.screens.home.order.HomeRailDefinition
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderState
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStateCodec
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailPublishPolicy
import com.nexio.tv.ui.screens.home.order.RailSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class PartialSyncSafetyTest {
    private val gson = Gson()
    private val codec = HomeRailOrderStateCodec(gson)
    private val fixedClock = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC)

    private fun publicDef(key: String, family: RailFamily, intra: Int = 0) = HomeRailDefinition(
        key = HomeRailKey(key),
        family = family,
        source = RailSource.PROVIDER_PUBLIC,
        title = key,
        enabled = true,
        defaultSortKey = DefaultSortKey(family.familyRank, intra),
        publishPolicy = RailPublishPolicy.PUBLISH_WHEN_NON_EMPTY,
    )

    private fun TestScope.buildStoreWithInitialState(
        initialOrderedKeys: List<HomeRailKey>,
    ): Pair<HomeRailOrderStore, MutableStateFlow<String?>> {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val initialState = HomeRailOrderState.Empty.copy(orderedKeys = initialOrderedKeys)
        val persisted = MutableStateFlow<String?>(codec.encode(initialState))
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        val store = HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = mockk(relaxed = true),
        )
        return store to persisted
    }

    @Test
    fun `tmdb-only sync preserves positions of trakt and simkl`() = runTest {
        val (store, _) = buildStoreWithInitialState(
            initialOrderedKeys = listOf(
                HomeRailKey("trakt_popular"),
                HomeRailKey("tmdb_popular_movies"),
                HomeRailKey("simkl_trending"),
                HomeRailKey("tmdb_top_rated_movies"),
            ),
        )

        // Seed live definitions so the splice can resolve family ranks.
        store.onLiveDefinitionsArrived(
            listOf(
                publicDef("trakt_popular", RailFamily.TRAKT),
                publicDef("tmdb_popular_movies", RailFamily.TMDB, 0),
                publicDef("simkl_trending", RailFamily.SIMKL),
                publicDef("tmdb_top_rated_movies", RailFamily.TMDB, 1),
            ),
        )

        applyCatalogsSection(
            payload = AccountConfigSyncPayload(
                catalogs = CatalogSyncSettings(
                    tmdb = TmdbCatalogSyncSettings(
                        catalogOrder = listOf("tmdb_top_rated_movies", "tmdb_popular_movies"),
                    ),
                ),
            ),
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true),
            simklSettingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true),
            mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true),
            tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true),
            kitsuCatalogSettingsDataStore = mockk<KitsuCatalogSettingsDataStore>(relaxed = true),
            homeRailOrderStore = store,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(
                HomeRailKey("trakt_popular"),
                HomeRailKey("tmdb_top_rated_movies"),
                HomeRailKey("simkl_trending"),
                HomeRailKey("tmdb_popular_movies"),
            ),
            store.state.first().orderedKeys,
        )
    }

    @Test
    fun `null home section does not erase orderedKeys`() = runTest {
        val (store, _) = buildStoreWithInitialState(
            initialOrderedKeys = listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
        )

        applyCatalogsSection(
            payload = AccountConfigSyncPayload(
                catalogs = CatalogSyncSettings(home = null),
            ),
            layoutPreferenceDataStore = mockk<LayoutPreferenceDataStore>(relaxed = true),
            traktSettingsDataStore = mockk<TraktSettingsDataStore>(relaxed = true),
            simklSettingsDataStore = mockk<SimklSettingsDataStore>(relaxed = true),
            mdbListSettingsDataStore = mockk<MDBListSettingsDataStore>(relaxed = true),
            tmdbCatalogSettingsDataStore = mockk<TmdbCatalogSettingsDataStore>(relaxed = true),
            kitsuCatalogSettingsDataStore = mockk<KitsuCatalogSettingsDataStore>(relaxed = true),
            homeRailOrderStore = store,
        )
        advanceUntilIdle()

        assertEquals(
            listOf(HomeRailKey("A"), HomeRailKey("B"), HomeRailKey("C")),
            store.state.first().orderedKeys,
        )
    }
}
