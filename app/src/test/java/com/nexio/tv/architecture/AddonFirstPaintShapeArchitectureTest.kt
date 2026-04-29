package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonFirstPaintShapeArchitectureTest {
    @Test
    fun `addon catalog dto models documented first paint aliases`() {
        val dtoSource = productionFile("data/remote/dto/CatalogResponseDto.kt").readText()

        assertTrue("Addon catalog root must remain the Stremio metas shape.", dtoSource.contains("@Json(name = \"metas\")"))
        assertTrue("TMDB addon search uses genre for first-paint genres.", dtoSource.contains("@Json(name = \"genre\")"))
        assertTrue("TMDB addon search uses year for first-paint release info.", dtoSource.contains("@Json(name = \"year\")"))
        assertTrue("TMDB addon catalog exposes imdb_id for identity harvest.", dtoSource.contains("@Json(name = \"imdb_id\")"))
        assertTrue("Catalog metas must wire behaviorHints into MetaPreviewDto.", dtoSource.contains("@Json(name = \"behaviorHints\")"))
        assertTrue("Top Streaming/TMDB payloads expose behaviorHints.defaultVideoId.", dtoSource.contains("data class CatalogBehaviorHintsDto"))
        assertTrue("Catalog behavior hints must model defaultVideoId.", dtoSource.contains("@Json(name = \"defaultVideoId\")"))
    }

    @Test
    fun `addon mapper feeds aliases and ids into shared meta preview`() {
        val mapperSource = productionFile("data/mapper/CatalogMapper.kt").readText()

        assertTrue("Addon first paint must use year when releaseInfo is absent.", mapperSource.contains("releaseInfo = releaseInfo ?: year"))
        assertTrue("Addon first paint must use genre when genres is absent.", mapperSource.contains("genres = genres ?: genre ?: emptyList()"))
        assertTrue("Addon mapper must harvest stable IDs for visible hydration.", mapperSource.contains("firstPaintStableIds = deriveAddonStableIds"))
        assertTrue("Addon mapper must derive TMDB id from tmdb-prefixed addon id.", mapperSource.contains("startsWith(\"tmdb:\""))
        assertTrue("Addon mapper must derive IMDb id from imdb_id/defaultVideoId/tt id.", mapperSource.contains("defaultVideoId"))
        assertTrue("Addon mapper must reject non-title IMDb-like IDs.", mapperSource.contains("^tt\\\\d+$"))
    }

    @Test
    fun `addon first paint does not introduce provider specific home lifecycle`() {
        data class ForbiddenLifecyclePattern(val pattern: Regex, val prefixGroup: Int)

        val providerSpecificLifecyclePatterns = listOf(
            ForbiddenLifecyclePattern(Regex("""\b([A-Za-z0-9]+)AddonHomeRenderer\b"""), 1),
            ForbiddenLifecyclePattern(Regex("""\b([A-Za-z0-9]+)AddonRenderer\b"""), 1),
            ForbiddenLifecyclePattern(Regex("""\b([A-Za-z0-9]+)AddonHydrationScheduler\b"""), 1),
            ForbiddenLifecyclePattern(Regex("""\b([A-Za-z0-9]+)PreviewHydrationScheduler\b"""), 1)
        )
        val allowedGenericPrefixes = setOf(
            "Addon",
            "Catalog",
            "Default",
            "External",
            "Generic",
            "Home",
            "Shared",
            "Unified"
        )
        val offenders = homeFiles().flatMap { file ->
            val source = file.readText()
            providerSpecificLifecyclePatterns.flatMap { lifecyclePattern ->
                lifecyclePattern.pattern.findAll(source).mapNotNull { match ->
                    val prefix = match.groupValues[lifecyclePattern.prefixGroup]
                    if (prefix in allowedGenericPrefixes) {
                        null
                    } else {
                        "${file.path}:${match.value}"
                    }
                }
            }
        }

        assertTrue(
            "Addon first-paint shape fixes must not introduce provider-specific Home renderer or hydration scheduler names: $offenders",
            offenders.isEmpty()
        )
    }

    private fun productionFile(relativePath: String): File =
        File("app/src/main/java/com/nexio/tv/$relativePath")

    private fun homeFiles(): List<File> =
        File("app/src/main/java/com/nexio/tv/ui/screens/home")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
}
