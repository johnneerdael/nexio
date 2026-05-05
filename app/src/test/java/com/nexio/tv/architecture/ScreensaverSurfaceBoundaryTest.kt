package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverSurfaceBoundaryTest {
    private val providerMetadataRatingArtworkDependencies = setOf(
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

    private val providerMetadataRatingArtworkCalls = setOf(
        "refreshCatalogToDisk",
        "getCatalogCachedFirst",
        "readCachedMeta",
        "enrichPreview",
        "resolveRequest",
        "resolveArtwork",
        "getMetaFromAllAddons",
        "getCachedMetaFromAllAddons",
        "hydrateAddonOriginItem"
    )

    private val candidateRepositoryBannedImportPrefixes = listOf(
        "com.nexio.tv.data.integration.metadata.",
        "com.nexio.tv.data.integration.trailer.",
        "com.nexio.tv.data.integration.youtube.",
        "com.nexio.tv.core.metadata.router.resolver.TrailerResolver"
    )

    private val candidateRepositoryBannedDependencies = providerMetadataRatingArtworkDependencies + setOf(
        "MetadataRepository",
        "RatingsRepository",
        "PosterRepository",
        "MetadataSecondaryRepository",
        "PosterRatingsUrlResolver",
        "TrailerBackendProvider",
        "TrailerTmdbProvider",
        "TrailerResolver",
        "TrailerService",
        "TrailerRepository",
        "TrailerMetadataRepository",
        "YouTubeTrailerIntegrationProvider",
        "KitsuMetadataProviderAdapter",
        "MetadataAdapterCandidates",
        "MetadataProviderTargetIds",
        "RuntimeMetadataIdentityLookup",
        "TmdbMetadataProviderAdapter",
        "TmdbOrganizationPersonAdapter",
        "TmdbRecommendationMetadataAdapter",
        "TmdbReviewMetadataAdapter",
        "TmdbTrailerMetadataAdapter",
        "TraktReviewMetadataAdapter",
        "TvdbMetadataProviderAdapter",
        "TvdbOrganizationPersonAdapter",
        "TvdbTrailerMetadataAdapter",
        "CustomImdbEpisodeRatingsRepository",
        "CustomImdbTitleRatingsRepository",
        "OmdbEpisodeRatingsRepository"
    )

    private val candidateRepositoryBannedCalls = providerMetadataRatingArtworkCalls + setOf(
        "resolveTrailer",
        "resolveIdleTrailerScreensaverPlaybackSource",
        "buildIdleTrailerYouTubeUrl",
        "resolveYouTubePlaybackSource",
        "resolveLatestAiredSeasonNumber",
        "fetchMovieVideos",
        "fetchTvVideos",
        "fetchSeasonVideos",
        "fetchTmdbEnrichment",
        "fetchMoreLikeThis",
        "fetchReviews",
        "fetchMovieCollection",
        "fetchSeasonEpisodes",
        "fetchKitsuAdvancedDetail",
        "findPersonIdByExactName",
        "resolvePosterArtworkRef",
        "resolvePosterUrl",
        "getActiveProvider",
        "currentSettings"
    )

    @Test
    fun `idle screensaver repository does not depend on provider metadata rating artwork or source pools`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverRepository.kt")

        assertEquals(
            "IdleScreensaverRepository must consume ScreensaverCandidateRepository output only.",
            emptyList<String>(),
            importedSimpleNames(source).intersect(providerMetadataRatingArtworkDependencies).sorted()
        )
        assertEquals(
            "IdleScreensaverRepository must not reference provider-specific dependencies.",
            emptyList<String>(),
            identifierReferences(source, providerMetadataRatingArtworkDependencies)
        )

        assertEquals(
            "IdleScreensaverRepository must not call provider enrichment or source-pool APIs.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = providerMetadataRatingArtworkCalls
            )
        )
    }

    @Test
    fun `screensaver candidate repository only projects resolved display surface state`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/ScreensaverCandidateRepository.kt")

        assertEquals(
            "ScreensaverCandidateRepository must not import provider metadata, rating, artwork, or trailer packages.",
            emptyList<String>(),
            bannedQualifiedImports(
                source = source,
                bannedPrefixes = candidateRepositoryBannedImportPrefixes,
                bannedSimpleNames = candidateRepositoryBannedDependencies
            )
        )
        assertEquals(
            "ScreensaverCandidateRepository must not import provider-specific metadata, rating, artwork, source-pool, or trailer services.",
            emptyList<String>(),
            importedSimpleNames(source).intersect(candidateRepositoryBannedDependencies).sorted()
        )
        assertEquals(
            "ScreensaverCandidateRepository must project from ResolvedDisplaySurfaceRepository, not reference provider-specific services.",
            emptyList<String>(),
            identifierReferences(source, candidateRepositoryBannedDependencies)
        )
        assertEquals(
            "ScreensaverCandidateRepository must not call provider enrichment, source-pool, artwork, metadata, rating, or trailer resolution APIs.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = candidateRepositoryBannedCalls
            )
        )
    }

    @Test
    fun `idle screensaver preparation does not call artwork router or rating metadata repositories`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/data/repository/IdleScreensaverPreparation.kt")

        assertEquals(
            "IdleScreensaverPreparation must remain a candidate-to-legacy-model projection.",
            emptyList<String>(),
            importedSimpleNames(source).intersect(providerMetadataRatingArtworkDependencies).sorted()
        )
        assertEquals(
            "IdleScreensaverPreparation must not reference artwork, metadata, or rating repositories.",
            emptyList<String>(),
            identifierReferences(source, providerMetadataRatingArtworkDependencies)
        )

        assertEquals(
            "IdleScreensaverPreparation must not call metadata, artwork, or rating resolution APIs.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = providerMetadataRatingArtworkCalls
            )
        )
    }

    @Test
    fun `main activity screensaver path does not construct youtube watch urls directly`() {
        val source = sourceOf("app/src/main/java/com/nexio/tv/MainActivity.kt")

        assertEquals(
            "Screensaver trailer playback must resolve through TrailerService, not direct URL construction.",
            emptyList<String>(),
            callReferences(
                source = source,
                names = setOf("buildIdleTrailerYouTubeUrl")
            )
        )
        assertEquals(
            "Screensaver trailer playback must not construct direct YouTube watch URLs.",
            emptyList<String>(),
            directYouTubeUrlReferences(source)
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

    private fun importedQualifiedNames(source: String): Set<String> =
        Regex("""(?m)^\s*import\s+([A-Za-z0-9_.]+)""")
            .findAll(source)
            .map { match -> match.groupValues[1] }
            .toSet()

    private fun bannedQualifiedImports(
        source: String,
        bannedPrefixes: List<String>,
        bannedSimpleNames: Set<String>
    ): List<String> =
        importedQualifiedNames(source)
            .filter { imported ->
                bannedPrefixes.any(imported::startsWith) ||
                    imported.substringAfterLast('.') in bannedSimpleNames
            }
            .sorted()

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

    private fun directYouTubeUrlReferences(source: String): List<String> {
        val normalized = source.withoutComments().normalizedForLiteralScan()
        return listOfNotNull(
            "youtube.com/watch".takeIf { normalized.contains("youtube.com/watch") },
            "watch?v=".takeIf { normalized.contains("watch?v=") },
            "youtu.be".takeIf { normalized.contains("youtu.be") }
        )
    }

    private fun String.withoutImports(): String =
        replace(Regex("""(?m)^\s*import\s+[A-Za-z0-9_.]+\s*$"""), " ")

    private fun String.withoutCommentsAndStrings(): String {
        return withoutComments().withoutStringLiterals()
    }

    private fun String.withoutComments(): String {
        val output = StringBuilder(length)
        var index = 0
        var inBlockComment = false
        var inLineComment = false
        while (index < length) {
            val current = this[index]
            val next = getOrNull(index + 1)
            when {
                inBlockComment && current == '*' && next == '/' -> {
                    inBlockComment = false
                    output.append(' ')
                    index += 2
                }
                inBlockComment -> {
                    output.append(' ')
                    index += 1
                }
                inLineComment && current == '\n' -> {
                    inLineComment = false
                    output.append(current)
                    index += 1
                }
                inLineComment -> {
                    output.append(' ')
                    index += 1
                }
                current == '/' && next == '*' -> {
                    inBlockComment = true
                    output.append(' ')
                    index += 2
                }
                current == '/' && next == '/' -> {
                    inLineComment = true
                    output.append(' ')
                    index += 2
                }
                else -> {
                    output.append(current)
                    index += 1
                }
            }
        }
        return output.toString()
    }

    private fun String.withoutStringLiterals(): String {
        val output = StringBuilder(length)
        var index = 0
        var inString = false
        while (index < length) {
            val current = this[index]
            when {
                inString && current == '\\' -> {
                    output.append(' ')
                    if (index + 1 < length) {
                        output.append(' ')
                    }
                    index += 2
                }
                inString && current == '"' -> {
                    inString = false
                    output.append(' ')
                    index += 1
                }
                inString -> {
                    output.append(' ')
                    index += 1
                }
                current == '"' -> {
                    inString = true
                    output.append(' ')
                    index += 1
                }
                else -> {
                    output.append(current)
                    index += 1
                }
            }
        }
        return output.toString()
    }

    private fun String.normalizedForLiteralScan(): String = buildString(length) {
        this@normalizedForLiteralScan.lowercase().forEach { char ->
            if (!char.isWhitespace() && char != '"' && char != '\'' && char != '+') {
                append(char)
            }
        }
    }
}
