package com.nexio.tv.core.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

class TvdbSecretAllowlistStaticTest {

    @Test
    fun `tvdb yml exists and declares the TVDB V4 API contract`() {
        val tvdbContractPath = Paths.get("apiblueprints/tvdb.yml")

        assertTrue("apiblueprints/tvdb.yml must be checked in", Files.exists(tvdbContractPath))
        val tvdbContract = readRepositoryFile("apiblueprints/tvdb.yml")
        assertTrue(
            "apiblueprints/tvdb.yml should include https://api4.thetvdb.com/v4/",
            tvdbContract.contains("https://api4.thetvdb.com/v4")
        )
    }

    @Test
    fun `supabase secret type allowlists include tvdb api key everywhere`() {
        val sql = readRepositoryFile("supabase/account_settings_sync.sql")
        val tvdbOccurrences = Regex("'tvdb_api_key'").findAll(sql).count()

        assertTrue(
            "Expected at least eight tvdb_api_key allowlist entries in account_settings_sync.sql",
            tvdbOccurrences >= 8
        )

        val secretTypeInBlocks = Regex(
            pattern = """(?:secret_type\s+text\s+not\s+null\s+)?check\s*\(\s*secret_type\s+in\s*\((.*?)\)""",
            options = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(sql).toList()

        assertTrue("Expected at least one secret_type in ( allowlist block", secretTypeInBlocks.isNotEmpty())
        secretTypeInBlocks.forEach { block ->
            assertTrue(
                "Every secret_type in ( allowlist block must include tvdb_api_key",
                block.value.contains("'tvdb_api_key'")
            )
        }
    }

    private fun readRepositoryFile(path: String): String {
        return String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
    }
}
