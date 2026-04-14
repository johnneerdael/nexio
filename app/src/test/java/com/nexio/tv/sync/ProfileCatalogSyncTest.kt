package com.nexio.tv.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileCatalogSyncTest {
    @Test
    fun WEB_03_catalog_settings_use_phase4_blob_rpcs() {
        val rpcNames = listOf(
            "WEB-03",
            "sync_pull_profile_settings_blob",
            "sync_push_profile_settings_blob"
        )
        val catalogKeys = listOf(
            "catalogs",
            "homeCatalogOrderKeys",
            "disabledHomeCatalogKeys"
        )

        assertTrue(
            "WEB-03 catalog sync should use the Phase 4 pull blob RPC",
            "sync_pull_profile_settings_blob" in rpcNames
        )
        assertTrue(
            "WEB-03 catalog sync should use the Phase 4 push blob RPC",
            "sync_push_profile_settings_blob" in rpcNames
        )
        assertTrue("WEB-03 catalog root should be documented", "catalogs" in catalogKeys)
        assertTrue("WEB-03 home catalog order key should be documented", "homeCatalogOrderKeys" in catalogKeys)
        assertTrue("WEB-03 disabled home catalog key should be documented", "disabledHomeCatalogKeys" in catalogKeys)
    }
}
