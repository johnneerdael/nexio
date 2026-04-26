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
}
