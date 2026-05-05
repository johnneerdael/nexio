package com.nexio.tv.ui.screens.home

import com.google.gson.Gson
import com.nexio.tv.core.sync.applyCatalogsSection
import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.KitsuCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.domain.model.CatalogRow
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
class HomeReactsToSyncReorderTest {
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

    private fun TestScope.buildStoreWithInitialOrder(
        initial: List<HomeRailKey>,
    ): HomeRailOrderStore {
        val layout = mockk<LayoutPreferenceDataStore>(relaxed = true)
        val initialState = HomeRailOrderState.Empty.copy(orderedKeys = initial)
        val persisted = MutableStateFlow<String?>(codec.encode(initialState))
        coEvery { layout.homeRailOrderStateJson } returns persisted
        coEvery { layout.homeCatalogOrderKeys } returns flowOf(emptyList())
        coEvery { layout.disabledHomeCatalogKeys } returns flowOf(emptyList())
        coEvery { layout.setHomeRailOrderStateJson(any()) } answers { persisted.value = firstArg() }

        return HomeRailOrderStore(
            layoutPreferenceDataStore = layout,
            codec = codec,
            clock = fixedClock,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            diagnostics = mockk(relaxed = true),
            profileManager = mockk<com.nexio.tv.core.profile.ProfileManager>(relaxed = true).also {
                io.mockk.every { it.activeProfileId } returns MutableStateFlow(0)
            },
        )
    }

    @Test
    fun `tmdb sync apply updates Modern Home rail order without restart`() = runTest {
        // Initial order on the device: trakt, tmdb-popular, simkl, tmdb-top-rated.
        val store = buildStoreWithInitialOrder(listOf(
            HomeRailKey("trakt_popular"),
            HomeRailKey("tmdb_popular_movies"),
            HomeRailKey("simkl_trending"),
            HomeRailKey("tmdb_top_rated_movies"),
        ))

        // Live definitions (the pipeline normally provides these every tick).
        val live = listOf(
            publicDef("trakt_popular", RailFamily.TRAKT),
            publicDef("tmdb_popular_movies", RailFamily.TMDB, 0),
            publicDef("simkl_trending", RailFamily.SIMKL),
            publicDef("tmdb_top_rated_movies", RailFamily.TMDB, 1),
        )

        // 1. Snapshot pre-sync row order via the materializer.
        store.onLiveDefinitionsArrived(live)
        val pre = materializeHomeRows(
            effectiveOrder = store.reconcileNow(live),
            liveSyntheticGroupsByKey = emptyMap(),
            persistedSyntheticGroupsByKey = emptyMap(),
            rawRowsByKey = mapOf(
                HomeRailKey("trakt_popular") to mockk<CatalogRow>(relaxed = true),
                HomeRailKey("tmdb_popular_movies") to mockk<CatalogRow>(relaxed = true),
                HomeRailKey("simkl_trending") to mockk<CatalogRow>(relaxed = true),
                HomeRailKey("tmdb_top_rated_movies") to mockk<CatalogRow>(relaxed = true),
            ),
            pendingRowsByKey = emptyMap(),
        )
        assertEquals(4, pre.size) // sanity

        // 2. Apply a TMDB-only sync that flips intra-TMDB order.
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

        // 3. Re-materialize. The pipeline would call reconcileNow + materializeHomeRows on the
        //    next tick — this is exactly what we exercise here.
        val effective = store.reconcileNow(live)
        assertEquals(
            listOf(
                HomeRailKey("trakt_popular"),
                HomeRailKey("tmdb_top_rated_movies"),
                HomeRailKey("simkl_trending"),
                HomeRailKey("tmdb_popular_movies"),
            ),
            effective.visibleKeys,
        )
    }

    @Test
    fun `kitsu sync apply updates Modern Home rail order without restart`() = runTest {
        val store = buildStoreWithInitialOrder(listOf(
            HomeRailKey("trakt_popular"),
            HomeRailKey("kitsu_trending_anime"),
            HomeRailKey("kitsu_popular_anime"),
        ))

        val live = listOf(
            publicDef("trakt_popular", RailFamily.TRAKT),
            publicDef("kitsu_trending_anime", RailFamily.KITSU, 0),
            publicDef("kitsu_popular_anime", RailFamily.KITSU, 1),
        )
        store.onLiveDefinitionsArrived(live)

        applyCatalogsSection(
            payload = AccountConfigSyncPayload(
                catalogs = CatalogSyncSettings(
                    kitsu = KitsuCatalogSyncSettings(
                        catalogOrder = listOf("kitsu_popular_anime", "kitsu_trending_anime"),
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

        val effective = store.reconcileNow(live)
        assertEquals(
            listOf(
                HomeRailKey("trakt_popular"),
                HomeRailKey("kitsu_popular_anime"),
                HomeRailKey("kitsu_trending_anime"),
            ),
            effective.visibleKeys,
        )
    }
}
