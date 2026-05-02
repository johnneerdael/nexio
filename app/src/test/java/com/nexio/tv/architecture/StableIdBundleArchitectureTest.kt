package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdBundleArchitectureTest {
    @Test
    fun `home renderer does not import stable id bundle resolver internals`() {
        val files = homeRendererFiles()
        val scannedNames = files.map { it.name }.toSet()
        val requiredSurfaces = setOf(
            "ClassicHomeContent.kt",
            "GridHomeContent.kt",
            "HomeScreen.kt",
            "HomeViewModelPresentationPipeline.kt",
            "ModernHomeContent.kt",
            "ModernHomeHero.kt",
            "ModernHomePresentation.kt",
            "ModernHomeRows.kt"
        )

        assertTrue(
            "Stable ID bundle renderer guard must cover known home renderer/presentation surfaces.",
            scannedNames.containsAll(requiredSurfaces)
        )

        files.forEach { file ->
            val text = file.readText()
            assertFalse(file.path, text.contains("StableIdBundleResolver"))
            assertFalse(file.path, text.contains("StableIdBundleRequest"))
        }
    }

    @Test
    fun `stable id resolver source does not chase simkl or trakt tracking ids`() {
        val text = File("app/src/main/java/com/nexio/tv/core/metadata/router/StableIdBundleResolver.kt").readText()

        assertFalse(text.contains("imdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("tmdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("tvdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("imdbToSimkl", ignoreCase = true))
        assertFalse(text.contains("tmdbToSimkl", ignoreCase = true))
        assertFalse(text.contains("tvdbToSimkl", ignoreCase = true))
        assertTrue(text.contains("MetadataPrimaryProvider.TRAKT"))
        assertTrue(text.contains("MetadataPrimaryProvider.SIMKL"))
    }

    private fun homeRendererFiles(): List<File> {
        val homeRoot = File("app/src/main/java/com/nexio/tv/ui/screens/home")
        val allowedInfrastructure = setOf(
            "CatalogPlan.kt",
            "HomeCatalogRefreshCoordinator.kt",
            "HomeFirstPaintMetadataMapper.kt",
            "HomePlaybackWorkGate.kt",
            "HomeProfileSession.kt",
            "HomeProviderLocalizedMetadataOverlay.kt",
            "HomeRailHydrationExecutor.kt",
            "HomeScreenFocusState.kt",
            "HomeUiState.kt"
        )

        return homeRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { file ->
                file.name in allowedInfrastructure ||
                    (file.name.startsWith("HomeViewModel") && file.name != "HomeViewModelPresentationPipeline.kt")
            }
            .toList()
    }
}
