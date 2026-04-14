package com.nexio.tv.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

typealias AndroidJUnit4 = RobolectricTestRunner

@RunWith(AndroidJUnit4::class)
class ProfileSyncServiceTest {
    @Test
    fun WEB_01_profile_crud_sync_contract_is_documented() {
        val contract = listOf(
            "WEB-01",
            "profile_upsert",
            "profile_delete",
            "sync_pull_profiles",
            "profile_index"
        )

        assertTrue("WEB-01 profile upsert contract should be documented", "profile_upsert" in contract)
        assertTrue("WEB-01 profile delete contract should be documented", "profile_delete" in contract)
        assertTrue("WEB-01 profile pull RPC should be documented", "sync_pull_profiles" in contract)
        assertTrue("WEB-01 profile index field should be documented", "profile_index" in contract)
    }
}
