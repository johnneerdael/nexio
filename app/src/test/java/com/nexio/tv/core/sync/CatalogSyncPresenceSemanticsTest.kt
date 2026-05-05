package com.nexio.tv.core.sync

import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.remote.supabase.AccountConfigSyncPayload
import com.nexio.tv.data.remote.supabase.CatalogSyncSettings
import com.nexio.tv.data.remote.supabase.HomeCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.MDBListCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.SimklCatalogSyncSettings
import com.nexio.tv.data.remote.supabase.TraktCatalogSyncSettings
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogSyncPresenceSemanticsTest {

    private fun newMocks(): Quartet {
        return Quartet(
            layout = mockk(relaxed = true),
            trakt = mockk(relaxed = true),
            simkl = mockk(relaxed = true),
            mdblist = mockk(relaxed = true)
        )
    }

    private suspend fun apply(payload: AccountConfigSyncPayload, mocks: Quartet) {
        applyCatalogsSection(
            payload = payload,
            layoutPreferenceDataStore = mocks.layout,
            traktSettingsDataStore = mocks.trakt,
            simklSettingsDataStore = mocks.simkl,
            mdbListSettingsDataStore = mocks.mdblist
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
    }

    @Test
    fun `trakt section with all empty lists calls trakt setter with cleared values`() = runTest {
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
    }

    @Test
    fun `null simkl section leaves simkl setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(simkl = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.simkl.setCatalogPreferences(any(), any()) }
    }

    @Test
    fun `simkl section with all empty lists calls simkl setter with cleared values`() = runTest {
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
    }

    @Test
    fun `null mdblist section leaves mdblist setter untouched`() = runTest {
        val mocks = newMocks()
        val payload = AccountConfigSyncPayload(
            catalogs = CatalogSyncSettings(mdblist = null)
        )
        apply(payload, mocks)
        coVerify(exactly = 0) { mocks.mdblist.setCatalogPreferences(any(), any(), any()) }
    }

    @Test
    fun `mdblist section with all empty lists calls mdblist setter with cleared values`() = runTest {
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
    }

    private data class Quartet(
        val layout: LayoutPreferenceDataStore,
        val trakt: TraktSettingsDataStore,
        val simkl: SimklSettingsDataStore,
        val mdblist: MDBListSettingsDataStore
    )
}
