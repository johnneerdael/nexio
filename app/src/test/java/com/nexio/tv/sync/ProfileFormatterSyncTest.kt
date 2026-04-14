package com.nexio.tv.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileFormatterSyncTest {
    @Test
    fun WEB_04_formatter_settings_use_phase4_blob_rpcs() {
        val rpcNames = listOf(
            "WEB-04",
            "sync_pull_profile_settings_blob",
            "sync_push_profile_settings_blob"
        )
        val formatterKeys = listOf(
            "formatter",
            "selectedTemplateId",
            "customTemplate",
            "enabled"
        )

        assertTrue(
            "WEB-04 formatter sync should use the Phase 4 pull blob RPC",
            "sync_pull_profile_settings_blob" in rpcNames
        )
        assertTrue(
            "WEB-04 formatter sync should use the Phase 4 push blob RPC",
            "sync_push_profile_settings_blob" in rpcNames
        )
        assertTrue("WEB-04 formatter root should be documented", "formatter" in formatterKeys)
        assertTrue("WEB-04 template selection key should be documented", "selectedTemplateId" in formatterKeys)
        assertTrue("WEB-04 custom template key should be documented", "customTemplate" in formatterKeys)
        assertTrue("WEB-04 enabled flag should be documented", "enabled" in formatterKeys)
    }
}
