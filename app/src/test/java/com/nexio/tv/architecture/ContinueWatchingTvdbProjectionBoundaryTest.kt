package com.nexio.tv.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ContinueWatchingTvdbProjectionBoundaryTest {
    @Test
    fun `production code does not use tvdb language mapper`() {
        val offenders = File("app/src/main/java/com/nexio/tv")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("TvdbLanguageMapper") }
            .map { it.invariantSeparatorsPath }
            .toList()

        if (offenders.isNotEmpty()) {
            fail("TVDB language mapping is forbidden; TVDB may only be used for season episode projection: $offenders")
        }
    }

    @Test
    fun `continue watching season projection uses projection-only tvdb episode API`() {
        val source = File("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt")
            .readText()

        assertTrue(
            "ContinueWatchingSnapshotService should use the projection-only facade method for TVDB season numbering projection",
            source.contains("fetchTvEpisodeProjection(")
        )
        assertFalse(
            "ContinueWatchingSnapshotService projection paths must not call localized episode enrichment",
            source.contains("fetchTvEpisodeEnrichment(")
        )
    }

    @Test
    fun `home continue watching tvdb items do not use localized metadata routes`() {
        val runtimePipeline = File(
            "app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatchingRuntimePipeline.kt"
        ).readText()
        val enrichment = File(
            "app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelContinueWatching.kt"
        ).readText()

        assertTrue(
            "TVDB continue-watching runtime should use projection-only episode metadata",
            runtimePipeline.contains("fetchTvEpisodeProjection(")
        )
        assertTrue(
            "TVDB continue-watching runtime must be explicitly separated from localized enrichment",
            runtimePipeline.contains("val isTvdbContent = contentId.startsWith(\"tvdb:\", ignoreCase = true)")
        )
        assertTrue(
            "TVDB continue-watching rows must bypass provider-localized overlay metadata",
            enrichment.contains("val localizedPreview = if (isTvdbContent)")
        )
        assertTrue(
            "TVDB continue-watching rows must not fetch localized episode descriptions",
            enrichment.contains("if (item.contentId().startsWith(\"tvdb:\", ignoreCase = true)) return null")
        )
    }
}
