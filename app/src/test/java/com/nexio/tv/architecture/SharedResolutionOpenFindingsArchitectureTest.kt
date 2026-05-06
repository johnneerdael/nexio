package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class SharedResolutionOpenFindingsArchitectureTest {
    @Test
    fun `detail view model does not own direct sidecar dependencies or tmdb identity bridging`() {
        val offenders = scanFile(
            file = requiredFile("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt"),
            forbiddenPatterns = linkedMapOf(
                "metadataSecondaryRepository." to Regex("""metadataSecondaryRepository\."""),
                "mdbListRepository." to Regex("""mdbListRepository\."""),
                "titleRatingOverrideRepository." to Regex("""titleRatingOverrideRepository\."""),
                "episodeRatingsSelectionRepository." to Regex("""episodeRatingsSelectionRepository\."""),
                "trailerService." to Regex("""trailerService\."""),
                "tmdbService.ensureTmdbId" to Regex("""tmdbService\.ensureTmdbId"""),
                ".ensureTmdbId(" to Regex("""\.ensureTmdbId\s*\(""")
            )
        )

        failIfNotEmpty(
            offenders,
            "MetaDetailsViewModel must consume shared resolved metadata surfaces instead of owning " +
                "sidecar repositories/services or TMDB identity bridge calls."
        )
    }

    @Test
    fun `metadata router facade does not resolve only for trace before sidecar fetches`() {
        val file = requiredFile("app/src/main/java/com/nexio/tv/core/metadata/router/MetadataRouterFacade.kt")
        val lines = file.readLines()
        val offenders = buildList {
            addAll(
                scanResolveThenSidecar(
                    file = file,
                    lines = lines,
                    sidecarName = "repo.fetchTmdbEnrichment",
                    sidecarPattern = Regex("""repo\.fetchTmdbEnrichment\b""")
                )
            )
            addAll(
                scanResolveThenSidecar(
                    file = file,
                    lines = lines,
                    sidecarName = "service.resolveTrailer",
                    sidecarPattern = Regex("""service\.resolveTrailer\b""")
                )
            )
            addAll(
                scanFile(
                    file = file,
                    forbiddenPatterns = linkedMapOf(
                        "MetadataSecondaryRepository construction error" to
                            Regex("""requires MetadataRouterFacade to be constructed with a non-null MetadataSecondaryRepository"""),
                        "TrailerService construction error" to
                            Regex("""requires MetadataRouterFacade to be constructed with a non-null TrailerService""")
                    )
                )
            )
        }

        failIfNotEmpty(
            offenders,
            "MetadataRouterFacade must not perform trace-only resolveRequest(metadataRequest) and then " +
                "delegate to sidecar repositories/services; move the behavior behind the shared resolver."
        )
    }

    @Test
    fun `home hydration and presentation do not call rating or trailer sidecars`() {
        val offenders = scanPathFiles(
            files = listOf(
                "app/src/main/java/com/nexio/tv/ui/screens/home/HomeHydrationCoordinator.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModelPresentationPipeline.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/home/HomeViewModel.kt"
            ),
            forbiddenPatterns = linkedMapOf(
                "TitleRatingOverrideRepository" to Regex("""\bTitleRatingOverrideRepository\b"""),
                "titleRatingOverrideRepository." to Regex("""titleRatingOverrideRepository\."""),
                "TrailerService" to Regex("""\bTrailerService\b"""),
                "trailerService." to Regex("""trailerService\."""),
                "getTitleMediaAvailability(" to Regex("""\bgetTitleMediaAvailability\s*\(""")
            )
        )

        failIfNotEmpty(
            offenders,
            "Home hydration and presentation code must consume shared resolved metadata instead of " +
                "rating/trailer sidecars."
        )
    }

    @Test
    fun `ui and main activity do not call tmdb identity bridge helpers`() {
        val offenders = scanFiles(
            files = productionKotlinFilesUnder("app/src/main/java/com/nexio/tv/ui") +
                requiredFile("app/src/main/java/com/nexio/tv/MainActivity.kt"),
            forbiddenPatterns = linkedMapOf(
                "ensureTmdbId(" to Regex("""(?:tmdbService\.)?ensureTmdbId\s*\(""")
            )
        )

        failIfNotEmpty(
            offenders,
            "UI and MainActivity must not call TMDB identity bridge helpers; route identity " +
                "resolution through shared metadata resolution surfaces."
        )
    }

    @Test
    fun `player code uses skip segment resolver instead of SkipIntroRepository directly`() {
        val offenders = scanPathFiles(
            files = listOf(
                "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerViewModel.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeController.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerObservers.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/player/PlayerRuntimeControllerPlaybackEvents.kt"
            ),
            forbiddenPatterns = linkedMapOf(
                "SkipIntroRepository" to Regex("""\bSkipIntroRepository\b"""),
                "skipIntroRepository." to Regex("""\bskipIntroRepository\.""")
            )
        )

        failIfNotEmpty(
            offenders,
            "Player code must use SkipSegmentResolver.resolveSkipSegments(request) instead of " +
                "calling SkipIntroRepository directly."
        )
    }

    @Test
    fun `screensaver models do not expose raw compatibility string artwork or trailer fields`() {
        val offenders = scanFile(
            file = requiredFile("app/src/main/java/com/nexio/tv/ui/screensaver/IdleScreensaverModels.kt"),
            forbiddenPatterns = linkedMapOf(
                "backgroundUrl: String" to Regex("""\bbackgroundUrl\s*:\s*String\b"""),
                "logoUrl: String" to Regex("""\blogoUrl\s*:\s*String\b"""),
                "trailerYtIds: List<String>" to Regex("""\btrailerYtIds\s*:\s*List<String>""")
            )
        )

        failIfNotEmpty(
            offenders,
            "Screensaver models must expose resolved artwork/trailer models instead of raw " +
                "compatibility string fields."
        )
    }

    @Test
    fun `metadata ui coil calls do not use raw remote artwork string fields`() {
        val offenders = scanPathFiles(
            files = listOf(
                "app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt",
                "app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt",
                "app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt"
            ),
            forbiddenPatterns = linkedMapOf(
                "Coil raw artwork data field" to Regex(
                    """\.data\s*\(\s*(?:posterUrl|imageUrl|logoUrl|backdropUrl|displayPoster|displayBackground|displayThumbnail|item\.imageUrl|url)\s*\)"""
                )
            )
        )

        failIfNotEmpty(
            offenders,
            "Metadata UI Coil requests must receive internal/local artwork models, not raw remote " +
                "artwork string fields."
        )
    }

    @Test
    fun `detail code does not perform cross provider localization fallback`() {
        val offenders = scanFile(
            file = requiredFile("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsViewModel.kt"),
            forbiddenPatterns = linkedMapOf(
                "tvEnrichment ?: tmdbEnrichment" to Regex("""tvEnrichment\s*\?:\s*tmdbEnrichment"""),
                "tmdbEnrichment ?: tvEnrichment" to Regex("""tmdbEnrichment\s*\?:\s*tvEnrichment"""),
                "tvDescription ?: tmdbDescription" to Regex("""tvDescription\s*\?:\s*tmdbDescription"""),
                "tmdbDescription ?: tvDescription" to Regex("""tmdbDescription\s*\?:\s*tvDescription"""),
                "tvOverview ?: tmdbOverview" to Regex("""tvOverview\s*\?:\s*tmdbOverview"""),
                "tmdbOverview ?: tvOverview" to Regex("""tmdbOverview\s*\?:\s*tvOverview""")
            )
        )

        failIfNotEmpty(
            offenders,
            "MetaDetailsViewModel must not perform cross-provider localization fallback; provider " +
                "selection belongs in shared resolution."
        )
    }

    private fun scanResolveThenSidecar(
        file: File,
        lines: List<String>,
        sidecarName: String,
        sidecarPattern: Regex
    ): List<String> {
        val resolveLines = lines.mapIndexedNotNull { index, line ->
            if (Regex("""\bresolveRequest\s*\(\s*metadataRequest\s*\)""").containsMatchIn(line)) index + 1 else null
        }
        if (resolveLines.isEmpty()) return emptyList()

        return lines.mapIndexedNotNull { index, line ->
            if (!sidecarPattern.containsMatchIn(line)) return@mapIndexedNotNull null
            val lineNumber = index + 1
            val priorResolve = resolveLines.lastOrNull { it < lineNumber } ?: return@mapIndexedNotNull null
            "${file.invariantSeparatorsPath}:$lineNumber:$sidecarName after resolveRequest(metadataRequest) at line $priorResolve"
        }
    }

    private fun scanFiles(
        files: List<File>,
        forbiddenPatterns: Map<String, Regex>
    ): List<String> =
        files.flatMap { file -> scanFile(file, forbiddenPatterns) }

    private fun scanPathFiles(
        files: List<String>,
        forbiddenPatterns: Map<String, Regex>
    ): List<String> =
        scanFiles(files.map(::requiredFile), forbiddenPatterns)

    private fun scanFile(
        file: File,
        forbiddenPatterns: Map<String, Regex>
    ): List<String> =
        file.readLines().flatMapIndexed { index, line ->
            forbiddenPatterns.mapNotNull { (label, pattern) ->
                if (pattern.containsMatchIn(line)) {
                    "${file.invariantSeparatorsPath}:${index + 1}:$label"
                } else {
                    null
                }
            }
        }

    private fun productionKotlinFilesUnder(path: String): List<File> {
        val root = requiredDirectory(path)
        return root.walkTopDown()
            .filter { file -> file.isFile && (file.extension == "kt" || file.extension == "java") }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }

    private fun requiredFile(path: String): File {
        val file = File(path)
        require(file.isFile) { "Required architecture target is missing: $path" }
        return file
    }

    private fun requiredDirectory(path: String): File {
        val file = File(path)
        require(file.isDirectory) { "Required architecture target directory is missing: $path" }
        return file
    }

    private fun failIfNotEmpty(offenders: List<String>, message: String) {
        if (offenders.isNotEmpty()) {
            fail("$message Offenders:\n${offenders.joinToString(separator = "\n")}")
        }
    }
}
