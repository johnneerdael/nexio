package com.nexio.tv.core.sync

import com.nexio.tv.data.local.KitsuCatalogSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TmdbCatalogSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.KitsuCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.SimklCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TmdbCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings
import com.nexio.tv.ui.screens.home.order.HomeRailKey
import com.nexio.tv.ui.screens.home.order.HomeRailOrderStore
import com.nexio.tv.ui.screens.home.order.RailFamily
import com.nexio.tv.ui.screens.home.order.RailOrderMutationSource
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSyncPresenceSemanticsTest {

    private fun newMocks(): Mocks {
        return Mocks(
            layout = mockk(relaxed = true),
            trakt = mockk(relaxed = true),
            simkl = mockk(relaxed = true),
            mdblist = mockk(relaxed = true),
            tmdbCatalog = mockk(relaxed = true),
            kitsuCatalog = mockk(relaxed = true),
            homeRailOrderStore = mockk(relaxed = true)
        )
    }

    private suspend fun apply(payload: AccountConfigSyncPayload, mocks: Mocks) {
        applyCatalogsSection(
            payload = payload,
            layoutPreferenceDataStore = mocks.layout,
            traktSettingsDataStore = mocks.trakt,
            simklSettingsDataStore = mocks.simkl,
            mdbListSettingsDataStore = mocks.mdblist,
            tmdbCatalogSettingsDataStore = mocks.tmdbCatalog,
            kitsuCatalogSettingsDataStore = mocks.kitsuCatalog,
            homeRailOrderStore = mocks.homeRailOrderStore
        )
    }

    @Test
    fun `null home section leaves layout setters untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(home = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.layout.setHeroCatalogKeys(any()) }
        coVerify(exactly = 0) { mocks.layout.setHomeCatalogOrderKeys(any()) }
        coVerify(exactly = 0) { mocks.layout.setDisabledHomeCatalogKeys(any()) }
    }

    @Test
    fun `non-null home section with null inner fields leaves layout setters untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(
                    heroCatalogKeys = null,
                    homeCatalogOrderKeys = null,
                    disabledHomeCatalogKeys = null
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.layout.setHeroCatalogKeys(any()) }
        coVerify(exactly = 0) { mocks.layout.setHomeCatalogOrderKeys(any()) }
        coVerify(exactly = 0) { mocks.layout.setDisabledHomeCatalogKeys(any()) }
    }

    @Test
    fun `non-null home section with empty homeCatalogOrderKeys calls layout setter with empty list`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(homeCatalogOrderKeys = emptyList())
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.layout.setHomeCatalogOrderKeys(emptyList()) }
        // hero/disabled remain null -> no-op
        coVerify(exactly = 0) { mocks.layout.setHeroCatalogKeys(any()) }
        coVerify(exactly = 0) { mocks.layout.setDisabledHomeCatalogKeys(any()) }
    }

    @Test
    fun `populated home section applies each non-null inner field`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                home = HomeCatalogSyncSettings(
                    heroCatalogKeys = listOf("hero-a"),
                    homeCatalogOrderKeys = listOf("row-a", "row-b"),
                    disabledHomeCatalogKeys = listOf("row-c")
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.layout.setHeroCatalogKeys(listOf("hero-a")) }
        coVerify(exactly = 1) { mocks.layout.setHomeCatalogOrderKeys(listOf("row-a", "row-b")) }
        coVerify(exactly = 1) { mocks.layout.setDisabledHomeCatalogKeys(listOf("row-c")) }
    }

    @Test
    fun `null trakt section leaves trakt setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(trakt = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.trakt.setCatalogPreferences(any(), any(), any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.TRAKT, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `trakt section with any null inner field leaves trakt setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                trakt = TraktCatalogSyncSettings(
                    catalogEnabledSet = emptyList(),
                    catalogOrder = emptyList(),
                    selectedPopularListKeys = null
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.trakt.setCatalogPreferences(any(), any(), any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.TRAKT, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `trakt section with all empty lists calls trakt setter with cleared values and reorders rails`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                trakt = TraktCatalogSyncSettings(
                    catalogEnabledSet = emptyList(),
                    catalogOrder = emptyList(),
                    selectedPopularListKeys = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) {
            mocks.trakt.setCatalogPreferences(
                enabledCatalogs = emptySet(),
                catalogOrder = emptyList(),
                selectedPopularListKeys = emptySet()
            )
        }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TRAKT,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    @Test
    fun `populated trakt section reorders rails with provider order`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                trakt = TraktCatalogSyncSettings(
                    catalogEnabledSet = listOf("trakt_up_next"),
                    catalogOrder = listOf("trakt_up_next", "trakt_recommended_movies"),
                    selectedPopularListKeys = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TRAKT,
                providerOrder = listOf(HomeRailKey("trakt_up_next"), HomeRailKey("trakt_recommended_movies")),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    @Test
    fun `null simkl section leaves simkl setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(simkl = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.simkl.setCatalogPreferences(any(), any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.SIMKL, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `simkl section with all empty lists calls simkl setter with cleared values and reorders rails`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                simkl = SimklCatalogSyncSettings(
                    catalogEnabledSet = emptyList(),
                    catalogOrder = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) {
            mocks.simkl.setCatalogPreferences(
                enabledCatalogs = emptySet(),
                catalogOrder = emptyList()
            )
        }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.SIMKL,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    @Test
    fun `null mdblist section leaves mdblist setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(mdblist = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.mdblist.setCatalogPreferences(any(), any(), any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.MDBLIST, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `mdblist section with all empty lists calls mdblist setter with cleared values and reorders rails`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                mdblist = MDBListCatalogSyncSettings(
                    hiddenPersonalListKeys = emptyList(),
                    selectedTopListKeys = emptyList(),
                    catalogOrder = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) {
            mocks.mdblist.setCatalogPreferences(
                hiddenPersonalListKeys = emptySet(),
                selectedTopListKeys = emptySet(),
                catalogOrder = emptyList()
            )
        }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.MDBLIST,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    // ----- TMDB tests -----

    @Test
    fun `null tmdb section is a no-op`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(catalogs = CatalogSyncSettings(tmdb = null))
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.tmdbCatalog.setEnabledCatalogs(any()) }
        coVerify(exactly = 0) { mocks.tmdbCatalog.setCatalogOrder(any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.TMDB, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `present tmdb section with null inner fields is a no-op`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(catalogEnabledSet = null, catalogOrder = null)
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.tmdbCatalog.setEnabledCatalogs(any()) }
        coVerify(exactly = 0) { mocks.tmdbCatalog.setCatalogOrder(any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.TMDB, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `tmdb section with empty catalogOrder is applied and reorders rails`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(
                    catalogEnabledSet = emptyList(),
                    catalogOrder = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.tmdbCatalog.setEnabledCatalogs(emptySet()) }
        coVerify(exactly = 1) { mocks.tmdbCatalog.setCatalogOrder(emptyList()) }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    @Test
    fun `populated tmdb section writes provider preference and reorderProviderKeys`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                tmdb = TmdbCatalogSyncSettings(
                    catalogEnabledSet = listOf("tmdb_trending_movies"),
                    catalogOrder = listOf("tmdb_trending_movies", "tmdb_popular_series")
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.tmdbCatalog.setEnabledCatalogs(setOf("tmdb_trending_movies")) }
        coVerify(exactly = 1) {
            mocks.tmdbCatalog.setCatalogOrder(listOf("tmdb_trending_movies", "tmdb_popular_series"))
        }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.TMDB,
                providerOrder = listOf(HomeRailKey("tmdb_trending_movies"), HomeRailKey("tmdb_popular_series")),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    // ----- Kitsu tests -----

    @Test
    fun `null kitsu section is a no-op`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(catalogs = CatalogSyncSettings(kitsu = null))
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.kitsuCatalog.setEnabledCatalogs(any()) }
        coVerify(exactly = 0) { mocks.kitsuCatalog.setCatalogOrder(any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.KITSU, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `present kitsu section with null inner fields is a no-op`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                kitsu = KitsuCatalogSyncSettings(catalogEnabledSet = null, catalogOrder = null)
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.kitsuCatalog.setEnabledCatalogs(any()) }
        coVerify(exactly = 0) { mocks.kitsuCatalog.setCatalogOrder(any()) }
        coVerify(exactly = 0) {
            mocks.homeRailOrderStore.reorderProviderKeys(family = RailFamily.KITSU, providerOrder = any(), source = any())
        }
    }

    @Test
    fun `kitsu section with empty catalogOrder is applied and reorders rails`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                kitsu = KitsuCatalogSyncSettings(
                    catalogEnabledSet = emptyList(),
                    catalogOrder = emptyList()
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.kitsuCatalog.setEnabledCatalogs(emptySet()) }
        coVerify(exactly = 1) { mocks.kitsuCatalog.setCatalogOrder(emptyList()) }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.KITSU,
                providerOrder = emptyList(),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    @Test
    fun `populated kitsu section writes provider preference and reorderProviderKeys`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(
                kitsu = KitsuCatalogSyncSettings(
                    catalogEnabledSet = listOf("kitsu_trending_anime"),
                    catalogOrder = listOf("kitsu_trending_anime", "kitsu_popular_anime")
                )
            )
        )
        apply(payload, mocks)
        coVerify(exactly = 1) { mocks.kitsuCatalog.setEnabledCatalogs(setOf("kitsu_trending_anime")) }
        coVerify(exactly = 1) {
            mocks.kitsuCatalog.setCatalogOrder(listOf("kitsu_trending_anime", "kitsu_popular_anime"))
        }
        coVerify(exactly = 1) {
            mocks.homeRailOrderStore.reorderProviderKeys(
                family = RailFamily.KITSU,
                providerOrder = listOf(HomeRailKey("kitsu_trending_anime"), HomeRailKey("kitsu_popular_anime")),
                source = RailOrderMutationSource.ACCOUNT_SYNC
            )
        }
    }

    private data class Mocks(
        val layout: LayoutPreferenceDataStore,
        val trakt: TraktSettingsDataStore,
        val simkl: SimklSettingsDataStore,
        val mdblist: MDBListSettingsDataStore,
        val tmdbCatalog: TmdbCatalogSettingsDataStore,
        val kitsuCatalog: KitsuCatalogSettingsDataStore,
        val homeRailOrderStore: HomeRailOrderStore
    )
}
