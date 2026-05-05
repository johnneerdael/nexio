package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ScreensaverSurfaceBoundaryTest {
    @Test
    fun `idle screensaver repository does not depend on provider metadata rating artwork or source pools`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt")

        val bannedDependencies = setOf(
            "TraktDiscoverySnapshotStore",
            "TraktSettingsDataStore",
            "AddonRepository",
            "CatalogRepository",
            "MetaRepository",
            "MDBListRepository",
            "MetadataRouterFacade",
            "ArtworkRouter",
            "ArtworkAssetRepository",
            "TitleRatingOverrideRepository"
        )

        assertEquals(
            "IdleScreensaverRepository must consume ScreensaverCandidateRepository output only.",
            emptyList<String>(),
            importedSimpleNames(source).intersect(bannedDependencies).sorted()
        )
        assertEquals(
            "IdleScreensaverRepository must not reference provider-specific dependencies.",
            emptyList<String>(),
            identifierReferences(source, bannedDependencies)
        )

        assertEquals(
            "IdleScreensaverRepository must not call provider enrichment or source-pool APIs.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = setOf(
                    "refreshCatalogToDisk",
                    "getCatalogCachedFirst",
                    "readCachedMeta",
                    "enrichPreview",
                    "resolveRequest",
                    "getMetaFromAllAddons",
                    "getCachedMetaFromAllAddons",
                    "hydrateAddonOriginItem"
                )
            )
        )
    }

    @Test
    fun `idle screensaver preparation does not call artwork router or rating metadata repositories`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt")

        val bannedDependencies = setOf(
            "ArtworkRouter",
            "ArtworkAssetRepository",
            "MDBListRepository",
            "MetadataRouterFacade",
            "TitleRatingOverrideRepository",
            "AddonRepository",
            "CatalogRepository",
            "MetaRepository"
        )

        assertEquals(
            "IdleScreensaverPreparation must remain a candidate-to-legacy-model projection.",
            emptyList<String>(),
            importedSimpleNames(source).intersect(bannedDependencies).sorted()
        )
        assertEquals(
            "IdleScreensaverPreparation must not reference artwork, metadata, or rating repositories.",
            emptyList<String>(),
            identifierReferences(source, bannedDependencies)
        )

        assertEquals(
            "IdleScreensaverPreparation must not call metadata, artwork, or rating resolution APIs.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = setOf(
                    "resolveArtwork",
                    "resolveRequest",
                    "enrichPreview",
                    "readCachedMeta",
                    "getCatalogCachedFirst",
                    "refreshCatalogToDisk",
                    "getMetaFromAllAddons",
                    "getCachedMetaFromAllAddons",
                    "hydrateAddonOriginItem"
                )
            )
        )
    }

    @Test
    fun `main activity screensaver path does not construct youtube watch urls directly`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/MainActivity.kt")

        assertFalse(
            "Screensaver trailer playback must resolve through TrailerService, not direct URL construction.",
            Regex("""\bbuildIdleTrailerYouTubeUrl\s*\(\s*trailerId\s*\)""").containsMatchIn(source)
        )
        assertFalse(
            "Screensaver trailer playback must not construct direct YouTube watch URLs.",
            source.contains("\"https://www.youtube.com/watch?v=\"")
        )
    }

    private fun sourceOf(path: String): String {
        val file = File(path)
        require(file.exists()) { "Missing source file: $path" }
        return file.readText()
    }

    private fun importedSimpleNames(source: String): Set<String> =
        Regex("""(?m)^\s*import\s+([A-Za-z0-9_.]+)""")
            .findAll(source)
            .map { match -> match.groupValues[1].substringAfterLast('.') }
            .toSet()

    private fun callReferences(source: String, names: Set<String>): List<String> {
        val code = source.withoutCommentsAndStrings()
        return names
            .filter { name -> Regex("""\b${Regex.escape(name)}\s*\(""").containsMatchIn(code) }
            .sorted()
    }

    private fun identifierReferences(source: String, names: Set<String>): List<String> {
        val code = source.withoutImports().withoutCommentsAndStrings()
        return names
            .filter { name -> Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(code) }
            .sorted()
    }

    private fun String.withoutImports(): String =
        replace(Regex("""(?m)^\s*import\s+[A-Za-z0-9_.]+\s*$"""), " ")

    private fun String.withoutCommentsAndStrings(): String {
        val withoutBlockComments = replace(Regex("""(?s)/\*.*?\*/"""), " ")
        val withoutLineComments = withoutBlockComments.replace(Regex("""(?m)//.*$"""), " ")
        return withoutLineComments.replace(Regex("\"(?:\\\\.|[^\"\\\\])*\""), " ")
    }
}
