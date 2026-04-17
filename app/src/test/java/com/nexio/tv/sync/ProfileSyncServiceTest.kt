package com.nexio.tv.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

typealias AndroidJUnit4 = RobolectricTestRunner

@RunWith(AndroidJUnit4::class)
class ProfileSyncServiceTest {
    @Test
    fun WEB_01_profile_crud_sync_contract_is_documented() {
        val sql = migrationSql("20260417000100_restore_profile_management_rpc_grants.sql")
        val requiredGrants = listOf(
            "profile_upsert(INT, TEXT, TEXT, TEXT, BOOLEAN)",
            "profile_delete(INT)",
            "sync_pull_profiles()",
            "sync_push_profiles(JSONB)",
            "sync_delete_profile(INT)",
            "profile_verify_pin(INT, TEXT)"
        )

        for (signature in requiredGrants) {
            assertTrue(
                "WEB-01 RPC $signature should be executable by authenticated users",
                "GRANT EXECUTE ON FUNCTION public.$signature TO authenticated;" in sql
            )
        }
    }

    private fun migrationSql(fileName: String): String {
        val candidates = listOf(
            File("supabase/migrations/$fileName"),
            File("../supabase/migrations/$fileName")
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not find migration $fileName from ${File(".").absolutePath}")

        return file.readText()
    }
}
