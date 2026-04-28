package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MetadataRouterBoundaryTest {
    private val productionRoots = listOf(File("app/src/main/java/com/nexio/tv"))

    @Test
    fun `production callers use metadata router facade instead of legacy metadata execution`() {
        val offenders = productionRegexScan(
            forbiddenPatterns = mapOf(
                "TvMetadataRouter" to Regex("""\bTvMetadataRouter\b"""),
                "ProviderMetadataRouter" to Regex("""\bProviderMetadataRouter\b"""),
                "TmdbMetadataService" to Regex("""\bTmdbMetadataService\b"""),
                "KitsuMetadataService" to Regex("""\bKitsuMetadataService\b"""),
                "TvdbMetadataService" to Regex("""\bTvdbMetadataService\b""")
            ),
            allowedPaths = productionAllowedPathSuffixes(
                "/com/nexio/tv/core/tvdb/ProviderMetadataRouter.kt",
                "/com/nexio/tv/core/tvdb/TvMetadataRouter.kt",
                "/com/nexio/tv/data/repository/EpisodeRatingsSelectionRepository.kt",
                "/com/nexio/tv/data/trailer/TrailerService.kt",
                "/com/nexio/tv/data/integration/metadata/TmdbMetadataProviderAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/TvdbMetadataProviderAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/KitsuMetadataProviderAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/MetadataSecondaryRepository.kt",
                // New adapters created during cluster A migration that reference legacy services in KDoc:
                "/com/nexio/tv/data/integration/metadata/TmdbReviewMetadataAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/TmdbRecommendationMetadataAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/TmdbTrailerMetadataAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/TmdbOrganizationPersonAdapter.kt",
                "/com/nexio/tv/data/integration/metadata/TvdbTrailerMetadataAdapter.kt"
            )
        )

        if (offenders.isNotEmpty()) {
            fail("Production metadata paths must not call legacy metadata execution directly: $offenders")
        }
    }

    @Test
    fun `metadata router package does not inject raw provider or network clients`() {
        val routerFiles = File("app/src/main/java/com/nexio/tv/core/metadata/router")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
        assertTrue("Metadata router package is missing.", routerFiles.isNotEmpty())

        val forbidden = linkedMapOf(
            "TmdbApi" to Regex("""\bTmdbApi\b"""),
            "TvdbApi" to Regex("""\bTvdbApi\b"""),
            "KitsuApi" to Regex("""\bKitsuApi\b"""),
            "OkHttpClient" to Regex("""\bOkHttpClient\b"""),
            "Retrofit" to Regex("""\bRetrofit\b"""),
            "AuthService" to Regex("""\b[A-Za-z0-9_]*AuthService\b""")
        )
        val offenders = routerFiles.mapNotNull { file ->
            val content = file.readText()
            val matches = forbidden
                .filterValues { pattern -> pattern.containsMatchIn(content) }
                .keys
                .toList()
            if (matches.isEmpty()) null else "${file.path}:${matches.joinToString(",")}"
        }

        if (offenders.isNotEmpty()) {
            fail("Metadata router package must not inject raw provider or network clients: $offenders")
        }
    }

    @Test
    fun `metadata router facade exists`() {
        val facade = File("app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt")

        assertTrue("MetadataRouterFacade.kt must exist.", facade.isFile)
        assertTrue(
            "MetadataRouterFacade.kt must declare MetadataRouterFacade.",
            Regex("""\bclass\s+MetadataRouterFacade\b""").containsMatchIn(facade.readText())
        )
    }

    @Test
    fun `home preview paths do not execute router preview requests`() {
        val homeFiles = File("app/src/main/java/com/nexio/tv/ui/screens/home")
            .walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()

        val offenders = homeFiles.filter { file ->
            Regex("""\bMetadataDepth\.PREVIEW\b""").containsMatchIn(file.readText())
        }.map { it.invariantSeparatorsPath }

        if (offenders.isNotEmpty()) {
            fail("Home preview rendering must stay addon-only and must not invoke MetadataRouter PREVIEW: $offenders")
        }
    }

    private fun productionRegexScan(
        forbiddenPatterns: Map<String, Regex>,
        allowedPaths: Set<String> = emptySet()
    ): List<String> =
        productionRoots.flatMap { root ->
            root.walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .filterNot { file -> allowedPaths.any { suffix -> file.invariantSeparatorsPath.endsWith(suffix) } }
                .flatMap { file ->
                    val content = file.readText()
                    forbiddenPatterns
                        .filterValues { pattern -> pattern.containsMatchIn(content) }
                        .keys
                        .map { match -> "${file.invariantSeparatorsPath}:$match" }
                }
        }.toList()

    private fun productionAllowedPathSuffixes(vararg suffixes: String): Set<String> =
        suffixes.toSet()
}
