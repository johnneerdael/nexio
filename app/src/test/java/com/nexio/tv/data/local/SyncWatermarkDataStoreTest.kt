package com.nexio.tv.data.local

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.nexio.tv.core.sync.AccountSettingsSectionKey
import com.nexio.tv.core.sync.SyncWatermarkSurface
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SyncWatermarkDataStoreTest {

    private fun newStore(): SyncWatermarkDataStore =
        SyncWatermarkDataStore(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun `unknown surface returns zero`() = runTest {
        val store = newStore()
        assertEquals(0L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
    }

    @Test
    fun `set then get returns persisted value`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 1_700_000L)
        assertEquals(1_700_000L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
    }

    @Test
    fun `profile-scoped watermarks isolate by profile`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1, ms = 100L)
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 2, ms = 200L)
        assertEquals(100L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1))
        assertEquals(200L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 2))
    }

    @Test
    fun `surfaces are independent`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 111L)
        store.set(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null, ms = 222L)
        assertEquals(111L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
        assertEquals(222L, store.get(SyncWatermarkSurface.ACCOUNT_SECRETS, profileId = null))
    }

    @Test
    fun `account settings section watermarks isolate by section`() = runTest {
        val store = newStore()
        store.clearAll()

        store.setAccountSettingsSection(
            AccountSettingsSectionKey.INTEGRATIONS_TMDB,
            ms = 111L
        )
        store.setAccountSettingsSection(
            AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION,
            ms = 222L
        )

        assertEquals(
            111L,
            store.getAccountSettingsSection(AccountSettingsSectionKey.INTEGRATIONS_TMDB)
        )
        assertEquals(
            222L,
            store.getAccountSettingsSection(AccountSettingsSectionKey.PLAYBACK_STREAM_SELECTION)
        )
        assertEquals(0L, store.get(SyncWatermarkSurface.ACCOUNT_SETTINGS_SECTION, profileId = null))
    }

    @Test
    fun `account settings section baselines persist by section`() = runTest {
        val store = newStore()
        store.clearAll()

        store.setAccountSettingsSectionBaselines(
            mapOf(
                AccountSettingsSectionKey.INTEGRATIONS_TMDB to """{"useArtwork":true}""",
                AccountSettingsSectionKey.CATALOGS_HOME to """{"homeCatalogOrderKeys":[]}"""
            )
        )

        assertEquals(
            mapOf(
                AccountSettingsSectionKey.INTEGRATIONS_TMDB to """{"useArtwork":true}""",
                AccountSettingsSectionKey.CATALOGS_HOME to """{"homeCatalogOrderKeys":[]}"""
            ),
            store.getAccountSettingsSectionBaselines()
        )
    }

    @Test
    fun `clearAll wipes every watermark`() = runTest {
        val store = newStore()
        store.set(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null, ms = 1L)
        store.set(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1, ms = 2L)
        store.setAccountSettingsSection(AccountSettingsSectionKey.CATALOGS_HOME, ms = 3L)
        store.setAccountSettingsSectionBaselines(
            mapOf(AccountSettingsSectionKey.CATALOGS_HOME to """{"homeCatalogOrderKeys":[]}""")
        )
        store.clearAll()
        assertEquals(0L, store.get(SyncWatermarkSurface.ACCOUNT_ADDONS, profileId = null))
        assertEquals(0L, store.get(SyncWatermarkSurface.PROFILE_SETTINGS, profileId = 1))
        assertEquals(0L, store.getAccountSettingsSection(AccountSettingsSectionKey.CATALOGS_HOME))
        assertEquals(emptyMap<AccountSettingsSectionKey, String>(), store.getAccountSettingsSectionBaselines())
    }
}
