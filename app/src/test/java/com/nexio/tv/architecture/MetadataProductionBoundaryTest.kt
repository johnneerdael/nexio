package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class MetadataProductionBoundaryTest {
    private val metadataCallerRoots = listOf(
        File("app/src/main/java/com/nexio/tv/ui"),
        File("app/src/main/java/com/nexio/tv/workers")
    )

    @Test
    fun `metadata ui repository paths do not import legacy provider execution`() {
        val forbidden = linkedMapOf(
            "ProviderMetadataRouter" to Regex("""\bProviderMetadataRouter\b"""),
            "TvMetadataRouter" to Regex("""\bTvMetadataRouter\b"""),
            "TmdbMetadataService" to Regex("""\bTmdbMetadataService\b"""),
            "KitsuMetadataService" to Regex("""\bKitsuMetadataService\b"""),
            "TvdbMetadataService" to Regex("""\bTvdbMetadataService\b"""),
            "TmdbApi" to Regex("""\bTmdbApi\b"""),
            "TvdbApi" to Regex("""\bTvdbApi\b"""),
            "KitsuApi" to Regex("""\bKitsuApi\b"""),
            "OkHttpClient" to Regex("""\bOkHttpClient\b"""),
            "Retrofit" to Regex("""\bRetrofit\b""")
        )

        val allowedSuffixes = setOf(
            "/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
        )

        val offenders = metadataCallerRoots.flatMap { root ->
            root.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filterNot { file -> allowedSuffixes.any { file.invariantSeparatorsPath.endsWith(it) } }
                .flatMap { file ->
                    val content = file.readText()
                    forbidden
                        .filterValues { it.containsMatchIn(content) }
                        .keys
                        .map { "${file.invariantSeparatorsPath}:$it" }
                }
        }

        if (offenders.isNotEmpty()) {
            fail("Legacy provider execution is forbidden in production metadata callers: $offenders")
        }
    }

    @Test
    fun `production metadata entrypoints use facade or repository ownership`() {
        val entrypoints = linkedMapOf(
            "home_catalog" to ProductionOwnershipPath(
                file = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt"),
                requiredSymbols = listOf("MetadataRouterFacade")
            ),
            "detail_screen" to ProductionOwnershipPath(
                file = File("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt"),
                requiredSymbols = listOf("MetadataRouterFacade")
            ),
            "player_start" to ProductionOwnershipPath(
                file = File("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt"),
                requiredSymbols = listOf("MetadataRouterFacade")
            ),
            "continue_watching_write" to ProductionOwnershipPath(
                file = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"),
                requiredSymbols = listOf("MetadataRouterFacade")
            ),
            "continue_watching_render" to ProductionOwnershipPath(
                file = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt"),
                requiredSymbols = listOf("MetadataRouterFacade", "ContinueWatchingSnapshotService")
            )
        )
        val forbidden = listOf(
            "ProviderMetadataRouter",
            "TvMetadataRouter",
            "TmdbMetadataService",
            "KitsuMetadataService",
            "TvdbMetadataService",
            "TmdbApi",
            "TvdbApi",
            "KitsuApi"
        )

        val failures = entrypoints.flatMap { (name, path) ->
            val text = path.file.readText()
            val missing = path.requiredSymbols
                .filterNot { symbol -> Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(text) }
                .map { symbol -> "$name missing required ownership symbol $symbol" }
            val legacy = forbidden
                .filter { symbol -> Regex("""\b${Regex.escape(symbol)}\b""").containsMatchIn(text) }
                .map { symbol -> "$name imports or references forbidden legacy symbol $symbol" }
            missing + legacy
        }

        if (failures.isNotEmpty()) {
            fail("Production metadata ownership is not proven:\n${failures.joinToString(separator = "\n")}")
        }
    }

    private data class ProductionOwnershipPath(
        val file: File,
        val requiredSymbols: List<String>
    )
}
