package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationProviderContractRegistryTest {
    private val registry = File("app/src/test/resources/integration/expected_integration_contracts.yaml")

    @Test
    fun `contract registry exists and has required sections`() {
        val source = registry.readText()

        listOf(
            "schemaVersion: 1",
            "reviewedAt: \"2026-04-25\"",
            "contractSources:",
            "headerPolicies:",
            "cacheContracts:",
            "apiShapes:"
        ).forEach { token ->
            assertTrue("Registry missing $token", source.contains(token))
        }
    }

    @Test
    fun `contract registry declares provenance for reviewed providers`() {
        val source = registry.readText()
        val providers = setOf("TMDB", "TVDB", "KITSU", "TRAKT", "SIMKL", "RPDB", "TOP_POSTERS", "THEINTRODB", "MDBLIST")

        providers.forEach { provider ->
            assertTrue("Missing provider provenance for $provider", source.contains("  $provider:"))
        }

        val sourceFiles = listOf(
            "tmdb-contract.md",
            "tvdb-contract.md",
            "kitsu-contract.md",
            "trakt-simkl.md",
            "rpdb-topposters.md",
            "tidb-mdblist.md"
        )
        sourceFiles.forEach { filename ->
            assertTrue("Missing blueprint source $filename", source.contains(filename))
        }
    }

    @Test
    fun `contract registry uses only approved lifecycle statuses`() {
        val approved = setOf(
            "ACTIVE_REQUIRED",
            "ACTIVE_RUNTIME_COVERED",
            "PLANNED_NOT_ACTIVE",
            "DORMANT_PROVIDER",
            "EXEMPT",
            "RETIRED"
        )
        val statuses = Regex("""lifecycleStatus:\s*([A-Z_]+)""")
            .findAll(registry.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertTrue("Expected at least one lifecycleStatus entry.", statuses.isNotEmpty())
        assertEquals(emptySet<String>(), statuses - approved)
    }

    @Test
    fun `contract registry includes primary authority and secondary resolver shapes`() {
        val source = registry.readText()
        val required = setOf(
            "tmdb.movie.core",
            "tmdb.tv.core",
            "tmdb.season.episodes",
            "tvdb.remoteid.lookup",
            "tvdb.series.extended",
            "tvdb.series.translation",
            "tvdb.series.episodes.season_type",
            "tvdb.series.episodes.language",
            "kitsu.discovery.anime",
            "kitsu.search.text",
            "kitsu.anime.core",
            "kitsu.anime.episodes",
            "kitsu.castings",
            "kitsu.anime_staff",
            "kitsu.anime_productions",
            "kitsu.media_relationships",
            "trakt.movie.comments",
            "trakt.show.comments",
            "rpdb.poster_template",
            "topposters.poster_template",
            "topposters.thumbnail",
            "mdblist.rating.batch",
            "theintrodb.media"
        )

        required.forEach { shapeId ->
            assertTrue("Missing contract for $shapeId", source.contains("  $shapeId:"))
        }
    }

    @Test
    fun `top posters thumbnail is active runtime covered shape`() {
        val source = registry.readText()
        val block = Regex("""(?ms)^  topposters\.thumbnail:\n(.*?)(?=^  [a-z0-9_.-]+:|\z)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertTrue("Missing topposters.thumbnail contract block", block.isNotBlank())
        assertTrue(block.contains("provider: TOP_POSTERS"))
        assertTrue(block.contains("lifecycleStatus: ACTIVE_RUNTIME_COVERED"))
        assertFalse(block.contains("lifecycleStatus: PLANNED_NOT_ACTIVE"))
        assertFalse(block.contains("lifecycleStatus: EXEMPT"))
        assertTrue(block.contains("headerPolicy: topposters-thumbnail-v1"))
        assertTrue(block.contains("defaultCachePolicy: CacheFirst"))
        assertTrue(block.contains("cacheContract: poster-generated-v1"))
        val cacheContract = Regex("""(?ms)^  poster-generated-v1:\n(.*?)(?=^  [a-z0-9_.-]+:|\z)""")
            .find(source)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        assertTrue("Missing poster-generated-v1 cache contract block", cacheContract.isNotBlank())
        assertTrue(cacheContract.contains("cachePolicy: CacheFirst"))
        assertTrue(cacheContract.contains("ttl: 24h"))
        assertTrue(cacheContract.contains("staleAfterExpiry: 7d"))
        assertFalse(block.contains("Coil", ignoreCase = true))
    }
}
