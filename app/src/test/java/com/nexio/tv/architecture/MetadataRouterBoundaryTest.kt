package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MetadataRouterBoundaryTest {
    @Test
    fun `production callers use metadata router facade instead of legacy tv metadata router`() {
        val offenders = productionRegexScan(
            forbiddenPatterns = mapOf("TvMetadataRouter" to Regex("""\bTvMetadataRouter\b""")),
            allowedPaths = productionAllowedPathSuffixes("/com/nexio/tv/core/tvdb/TvMetadataRouter.kt")
        )

        if (offenders.isNotEmpty()) {
            fail("Production callers must depend on MetadataRouterFacade, not TvMetadataRouter: $offenders")
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
}
