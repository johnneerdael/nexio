package com.nexio.tv.architecture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContinueWatchingTvdbProjectionBoundaryTest {
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
