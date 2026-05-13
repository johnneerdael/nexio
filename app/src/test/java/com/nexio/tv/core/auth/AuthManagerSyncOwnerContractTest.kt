package com.nexio.tv.core.auth

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthManagerSyncOwnerContractTest {
    @Test
    fun `effective user resolution uses deployed sync owner rpc`() {
        val source = File("app/src/main/java/com/nexio/tv/core/auth/AuthManager.kt").readText()
        val migrations = File("supabase/migrations")
            .walkTopDown()
            .filter { it.isFile && it.extension == "sql" }
            .joinToString("\n") { it.readText() }

        assertTrue(source.contains("""postgrest.rpc("sync_owner_id")"""))
        assertFalse(source.contains("""postgrest.rpc("get_sync_owner")"""))
        assertTrue(migrations.contains("FUNCTION \"public\".\"sync_owner_id\"()"))
        assertTrue(migrations.contains("function public.get_sync_owner()"))
    }
}
