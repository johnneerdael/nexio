package com.nexio.tv.ui.shared

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Static source-level assertions that prove TVDB advanced metadata reaches
 * existing UI surfaces through shared `Meta` and `HomeDisplayMetadata` propagation
 * paths, without adding provider-specific UI sections (D-12).
 */
class MetaSharedTvdbSurfacePropagationTest {

    private val projectRoot: String
        get() = System.getProperty("user.dir")
            ?: error("Cannot determine project root")

    private fun sourceOf(relativePath: String): String {
        val file = File(projectRoot, relativePath)
        assertTrue("Source file must exist: $relativePath", file.exists())
        return file.readText()
    }

    @Test
    fun `home shared metadata path carries tvdb meta into existing preview fields`() {
        // HomeDisplayMetadata.kt must have Meta.toHomeDisplayMetadata with key field assignments
        val homeDisplay = sourceOf("app/src/main/java/com/nexio/tv/domain/model/HomeDisplayMetadata.kt")
        assertTrue(
            "HomeDisplayMetadata.kt must contain fun Meta.toHomeDisplayMetadata()",
            homeDisplay.contains("fun Meta.toHomeDisplayMetadata()")
        )
        assertTrue(
            "HomeDisplayMetadata.kt must assign genres = genres",
            homeDisplay.contains("genres = genres")
        )
        assertTrue(
            "HomeDisplayMetadata.kt must assign description = description",
            homeDisplay.contains("description = description")
        )
        assertTrue(
            "HomeDisplayMetadata.kt must assign runtime = runtime",
            homeDisplay.contains("runtime = runtime")
        )
        assertTrue(
            "HomeDisplayMetadata.kt must assign imdbRating = imdbRating",
            homeDisplay.contains("imdbRating = imdbRating")
        )

        // HomeViewModelPresentationPipeline.kt must merge external meta fields into preview
        val pipeline = sourceOf("app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt")
        assertTrue(
            "Pipeline must contain externalMeta.genres",
            pipeline.contains("externalMeta.genres")
        )
        assertTrue(
            "Pipeline must contain externalMeta.description",
            pipeline.contains("externalMeta.description")
        )
        assertTrue(
            "Pipeline must contain externalMeta.imdbRating",
            pipeline.contains("externalMeta.imdbRating")
        )
        assertTrue(
            "Pipeline must contain externalMeta.trailerYtIds",
            pipeline.contains("externalMeta.trailerYtIds")
        )
        assertTrue(
            "Pipeline must contain externalMeta.language",
            pipeline.contains("externalMeta.language")
        )
    }

    @Test
    fun `stream screen shared metadata path reads hydrated meta genres runtime and artwork`() {
        val stream = sourceOf("app/src/main/java/com/nexio/tv/ui/screens/stream/StreamScreenViewModel.kt")
        assertTrue(
            "StreamScreenViewModel.kt must contain val metaGenres = meta.genres",
            stream.contains("val metaGenres = meta.genres")
        )
        assertTrue(
            "StreamScreenViewModel.kt must contain extractRuntimeMinutes(meta)",
            stream.contains("extractRuntimeMinutes(meta)")
        )
        assertTrue(
            "StreamScreenViewModel.kt must contain genres = genresValue",
            stream.contains("genres = genresValue")
        )
    }

    @Test
    fun `screensaver shared metadata path carries tvdb meta into slides`() {
        val screensaver = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt")
        assertTrue(
            "IdleScreensaverPreparation.kt must contain externalMeta?.toHomeDisplayMetadata()",
            screensaver.contains("externalMeta?.toHomeDisplayMetadata()")
        )
        assertTrue(
            "IdleScreensaverPreparation.kt must contain genres = preview.genres",
            screensaver.contains("genres = preview.genres")
        )
    }

    @Test
    fun `player runtime shared metadata path reads tvdb cast members from meta`() {
        val player = sourceOf("app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerMetadata.kt")
        assertTrue(
            "PlayerRuntimeControllerMetadata.kt must contain sanitizeCastMembers(meta)",
            player.contains("sanitizeCastMembers(meta)")
        )
        assertTrue(
            "PlayerRuntimeControllerMetadata.kt must assign castMembers from safeCastMembers",
            player.contains("castMembers = if (safeCastMembers.isNotEmpty()) safeCastMembers else state.castMembers")
        )
    }
}
