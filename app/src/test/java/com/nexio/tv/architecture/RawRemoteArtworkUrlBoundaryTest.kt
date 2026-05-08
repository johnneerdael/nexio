package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RawRemoteArtworkUrlBoundaryTest {
    @Test
    fun `scanner catches raw remote provider artwork urls in final image contexts`() {
        val cases = mapOf(
            "tmdb" to "https://image.tmdb.org/t/p/w780/final.jpg",
            "tvdb" to "https://artworks.thetvdb.com/banners/final.jpg",
            "kitsu" to "https://media.kitsu.io/anime/poster_images/1/original.jpg",
            "rpdb" to "https://api.ratingposterdb.com/poster/final.jpg",
            "topposters" to "https://api.top-posters.com/poster/final.jpg",
            "addon_preview" to "https://addon.example.test/poster/final.jpg",
            "rail_preview" to "https://rail.example.test/poster/final.jpg"
        )

        val offenders = cases.flatMap { (name, url) ->
            rawRemoteArtworkUrlViolations(
                filePath = "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-$name.kt",
                text = """
                    val model = ImageRequest.Builder(context)
                        .data("$url")
                        .build()
                """.trimIndent()
            )
        }

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-tmdb.kt:2:image-model,data/http-url,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-tvdb.kt:2:image-model,data/http-url,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-kitsu.kt:2:image-model,data/http-url,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-rpdb.kt:2:image-model,data/http-url,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-topposters.kt:2:image-model,data/http-url,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-addon_preview.kt:2:image-model,data/http-url",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeHero-rail_preview.kt:2:image-model,data/http-url"
            ),
            offenders
        )
    }

    @Test
    fun `scanner catches raw remote provider artwork assigned through local variables`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt",
            text = """
                val posterModel = "https://image.tmdb.org/t/p/w500/from-variable.jpg"
                val model = ImageRequest.Builder(context)
                    .data(posterModel)
                    .build()
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:1:image-model,provider-domain",
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:3:image-model,data/raw-url-variable"
            ),
            offenders
        )
    }

    @Test
    fun `scanner rejects raw artwork model fields passed directly to coil`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt",
            text = """
                val imageModel = ImageRequest.Builder(context)
                    .data(item.background ?: item.poster)
                    .build()
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:2:final-artwork-assignment,image-model,data/raw-artwork-field,assignment/raw-artwork-field"
            ),
            offenders
        )
    }

    @Test
    fun `scanner rejects raw artwork fallback assignments in display models`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/screens/home/FakeModels.kt",
            text = """
                val heroPreview = HeroPreview(
                    poster = displayMetadata.displayPoster ?: item.progress.poster,
                    backdrop = firstNonBlank(displayMetadata.displayBackdrop, item.progress.backdrop)
                )
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeModels.kt:2:final-artwork-assignment,assignment/raw-artwork-field",
                "app/src/main/java/com/nexio/tv/ui/screens/home/FakeModels.kt:3:final-artwork-assignment,assignment/raw-artwork-field"
            ),
            offenders
        )
    }

    @Test
    fun `scanner rejects raw artwork fields in route handoff`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/navigation/FakeRoutes.kt",
            text = """
                Screen.Player.createRoute(
                    poster = entry.poster,
                    backdrop = playbackInfo.backdrop,
                    logo = item.info.logo
                )
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/navigation/FakeRoutes.kt:2:final-artwork-assignment,assignment/raw-artwork-field",
                "app/src/main/java/com/nexio/tv/ui/navigation/FakeRoutes.kt:3:final-artwork-assignment,assignment/raw-artwork-field",
                "app/src/main/java/com/nexio/tv/ui/navigation/FakeRoutes.kt:4:final-artwork-assignment,assignment/raw-artwork-field"
            ),
            offenders
        )
    }

    @Test
    fun `scanner rejects raw artwork fields assigned to image model variables`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt",
            text = """
                val imageModel = if (useLandscapePosters) {
                    item.background ?: item.poster
                } else {
                    item.poster ?: item.background
                }
                val request = ImageRequest.Builder(context)
                    .data(imageModel)
                    .build()
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:2:final-artwork-assignment,assignment/raw-artwork-field",
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:4:final-artwork-assignment,image-model,assignment/raw-artwork-field",
                "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt:7:image-model,data/raw-artwork-variable"
            ),
            offenders
        )
    }

    @Test
    fun `scanner allows display artwork projections passed to coil`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt",
            text = """
                val displayPoster = item.displayPoster
                val imageModel = ImageRequest.Builder(context)
                    .data(displayPoster)
                    .build()
            """.trimIndent()
        )

        assertTrue("Display artwork projections should not be reported: $offenders", offenders.isEmpty())
    }

    @Test
    fun `scanner recognizes allowed internal and local artwork models`() {
        val offenders = rawRemoteArtworkUrlViolations(
            filePath = "app/src/main/java/com/nexio/tv/ui/components/FakeCard.kt",
            text = """
                val internalModel = ImageRequest.Builder(context)
                    .data("nexio-artwork://movie/tt123/poster")
                    .build()
                val placeholderModel = ImageRequest.Builder(context)
                    .data("nexio-placeholder://movie/tt123/backdrop")
                    .build()
                val localModel = ImageRequest.Builder(context)
                    .data(File(repositoryDir, "poster.jpg"))
                    .build()
                val fileUriModel = ImageRequest.Builder(context)
                    .data("file:///repository/artwork/poster.jpg")
                    .build()
                val contentUriModel = ImageRequest.Builder(context)
                    .data("content://com.nexio.tv.artwork/poster/tt123")
                    .build()
                val resourceModel = ImageRequest.Builder(context)
                    .data(R.drawable.poster_placeholder)
                    .build()
            """.trimIndent()
        )

        assertTrue("Allowed internal artwork models should not be reported: $offenders", offenders.isEmpty())
    }

    @Test
    fun `metadata ui rendering paths do not own raw remote provider artwork urls`() {
        val offenders = metadataUiArtworkFiles()
            .flatMap { file ->
                rawRemoteArtworkUrlViolations(
                    filePath = file.invariantSeparatorsPath,
                    text = file.readText()
                )
            }

        if (offenders.isNotEmpty()) {
            fail(
                "Metadata UI rendering paths must receive internal/local artwork models, not raw provider artwork URLs:\n" +
                    offenders.joinToString(separator = "\n")
            )
        }
    }

    @Test
    fun `metadata display projection surfaces do not expose raw premium provider urls`() {
        val offenders = metadataDisplayProjectionFiles()
            .flatMap { file ->
                rawPremiumProviderUrlViolations(
                    filePath = file.invariantSeparatorsPath,
                    text = file.readText()
                )
            }

        if (offenders.isNotEmpty()) {
            fail(
                "Metadata/domain/display projection surfaces must not expose raw premium provider URLs. " +
                    "Use nexio-artwork://asset/..., nexio-artwork://decision/..., or nexio-placeholder://... instead:\n" +
                    offenders.joinToString(separator = "\n")
            )
        }
    }

    @Test
    fun `home catalog refresh does not call legacy poster ratings apply`() {
        val file = File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeCatalogRefreshCoordinator.kt")
        val offenders = file.readText()
            .lines()
            .mapIndexedNotNull { index, line ->
                if (legacyPosterRatingsApplyRegex.containsMatchIn(line)) {
                    "${file.invariantSeparatorsPath}:${index + 1}:legacy-poster-ratings-apply"
                } else {
                    null
                }
            }

        assertTrue(
            "Home refresh must use applyArtworkRef/currentSettings instead of legacy raw provider apply:\n" +
                offenders.joinToString(separator = "\n"),
            offenders.isEmpty()
        )
    }

    @Test
    fun `production snapshot writers do not persist raw premium urls`() {
        val files = listOf(
            File("app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt"),
            File("app/src/main/java/com/nexio/tv/data/local/ContinueWatchingSnapshotStore.kt"),
            File("app/src/main/java/com/nexio/tv/data/local/HydratedHomeOverlayStore.kt")
        )
        val forbidden = Regex("""(?:https://)?api\.(top-posters|ratingposterdb)\.com""")
        val allowlistedHits = mutableListOf<Pair<String, String>>()
        val offenders = files.flatMap { file ->
            file.readText()
                .lines()
                .mapIndexedNotNull { index, line ->
                    val location = "${file.invariantSeparatorsPath}:${index + 1}"
                    val allowlistIndex = expectedSnapshotSanitizerPrefixLocations.indexOf(location)
                    if (
                        forbidden.containsMatchIn(line) &&
                        line.isPremiumProviderSanitizerAllowlistEntry() &&
                        allowlistIndex >= 0
                    ) {
                        allowlistedHits += location to line.trim()
                        null
                    } else if (
                        forbidden.containsMatchIn(line)
                    ) {
                        "$location:raw-premium-provider-url"
                    } else {
                        null
                    }
                }
        }

        assertTrue(
            "Snapshot writers must not hard-code or persist raw premium provider URLs:\n" +
                offenders.joinToString(separator = "\n"),
            offenders.isEmpty()
        )
        assertEquals(
            "Only the existing HomeCatalogSnapshotStore sanitizer prefixes may be allowlisted",
            expectedSnapshotSanitizerAllowlist,
            allowlistedHits
        )
    }

    private fun metadataUiArtworkFiles(): List<File> {
        val roots = listOf(
            File("app/src/main/java/com/nexio/tv/ui/screens/home"),
            File("app/src/main/java/com/nexio/tv/ui/screens/detail"),
            File("app/src/main/java/com/nexio/tv/ui/screens/player")
        )
        val componentFiles = listOf(
            File("app/src/main/java/com/nexio/tv/ui/navigation/NexioNavHost.kt"),
            File("app/src/main/java/com/nexio/tv/ui/navigation/Screen.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/CatalogSeeAllScreen.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/ContentCard.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/GridContentCard.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/home/HomeScreen.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/search/SearchScreen.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/detail/MetaDetailsScreen.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/detail/HeroSection.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/detail/EpisodesSection.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/CatalogRowSection.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/ContinueWatchingSection.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/GridContinueWatchingSection.kt"),
            File("app/src/main/java/com/nexio/tv/ui/components/HeroCarousel.kt")
        )

        return roots
            .flatMap { root ->
                require(root.isDirectory) { "Required metadata UI root is missing: ${root.path}" }
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .toList()
            }
            .plus(componentFiles.onEach { file ->
                require(file.isFile) { "Required metadata UI component is missing: ${file.path}" }
            })
            .distinctBy { it.invariantSeparatorsPath }
            .sortedBy { it.invariantSeparatorsPath }
    }

    private fun metadataDisplayProjectionFiles(): List<File> {
        val roots = listOf(
            File("app/src/main/java/com/nexio/tv/domain/model"),
            File("app/src/main/java/com/nexio/tv/core/metadata/router"),
            File("app/src/test/java/com/nexio/tv/domain/model"),
            File("app/src/test/java/com/nexio/tv/core/metadata/router"),
            File("app/src/test/java/com/nexio/tv/metadata/audit")
        )

        return roots
            .flatMap { root ->
                require(root.isDirectory) { "Required metadata display projection root is missing: ${root.path}" }
                root.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .filterNot { it.invariantSeparatorsPath == RAW_BOUNDARY_TEST_PATH }
                    .toList()
            }
            .distinctBy { it.invariantSeparatorsPath }
            .sortedBy { it.invariantSeparatorsPath }
    }

    private fun rawPremiumProviderUrlViolations(
        filePath: String,
        text: String
    ): List<String> =
        text.lines().flatMapIndexed { index, line ->
            if (premiumProviderUrlLiteralRegex.containsMatchIn(line)) {
                listOf("$filePath:${index + 1}:raw-premium-provider-url")
            } else {
                emptyList()
            }
        }

    private fun rawRemoteArtworkUrlViolations(
        filePath: String,
        text: String
    ): List<String> {
        val lines = text.lines()
        val localRawUrlVariables = lines.localRawUrlVariables()
        val localRawArtworkVariables = lines.localRawArtworkVariables()
        return lines.flatMapIndexed { index, line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("//")) {
                emptyList()
            } else {
                val context = lines.contextAround(index).joinToString("\n")
                val reasons = buildList {
                    val hasRawUrlLiteral = rawUrlLiteralRegex.containsMatchIn(line)
                    val rawUrlVariablesInLine = localRawUrlVariables.filter { variable ->
                        variableReferenceRegex(variable).containsMatchIn(line)
                    }
                    val rawArtworkVariablesInLine = localRawArtworkVariables.filter { variable ->
                        variableReferenceRegex(variable).containsMatchIn(line)
                    }
                    val hasRawArtworkDataCall = dataCallRegex.containsMatchIn(line) &&
                        rawArtworkFieldReferenceRegex.containsMatchIn(line)
                    val hasRawArtworkAssignment = finalArtworkContextRegex.containsMatchIn(context) &&
                        rawArtworkFieldReferenceRegex.containsMatchIn(line)
                    if (
                        hasRawUrlLiteral ||
                        rawUrlVariablesInLine.isNotEmpty() ||
                        hasRawArtworkDataCall ||
                        hasRawArtworkAssignment
                    ) {
                        if (finalArtworkContextRegex.containsMatchIn(context)) add("final-artwork-assignment")
                        if (imageModelContextRegex.containsMatchIn(context)) add("image-model")
                    }
                    if (hasRawArtworkAssignment) add("final-artwork-assignment")
                    if (hasRawUrlLiteral && dataCallHttpLiteralRegex.containsMatchIn(context)) add("data/http-url")
                    if (rawUrlVariablesInLine.isNotEmpty() && dataCallRawUrlVariableRegex.containsMatchIn(line)) {
                        add("data/raw-url-variable")
                    }
                    if (rawArtworkVariablesInLine.isNotEmpty() && dataCallRawUrlVariableRegex.containsMatchIn(line)) {
                        if (imageModelContextRegex.containsMatchIn(context)) add("image-model")
                        add("data/raw-artwork-variable")
                    }
                    if (hasRawArtworkDataCall) add("data/raw-artwork-field")
                    if (hasRawArtworkAssignment) add("assignment/raw-artwork-field")
                    if (hasRawUrlLiteral && providerDomainLiteralRegex.containsMatchIn(line)) add("provider-domain")
                }

                if (reasons.isEmpty()) {
                    emptyList()
                } else {
                    listOf("$filePath:${index + 1}:${reasons.distinct().joinToString(",")}")
                }
            }
        }
    }

    private fun List<String>.localRawUrlVariables(): Set<String> {
        return mapNotNull { line ->
            val match = localRawUrlVariableRegex.find(line) ?: return@mapNotNull null
            match.groupValues[1]
        }.toSet()
    }

    private fun List<String>.localRawArtworkVariables(): Set<String> {
        val variables = linkedSetOf<String>()
        forEachIndexed { index, line ->
            val match = localArtworkModelVariableRegex.find(line) ?: return@forEachIndexed
            val variable = match.groupValues[1]
            val context = contextAround(index)
            if (context.any { rawArtworkFieldReferenceRegex.containsMatchIn(it) }) {
                variables += variable
            }
        }
        return variables
    }

    private fun List<String>.contextAround(index: Int): List<String> {
        val start = (index - CONTEXT_RADIUS).coerceAtLeast(0)
        val endExclusive = (index + CONTEXT_RADIUS + 1).coerceAtMost(size)
        return subList(start, endExclusive)
    }

    private fun String.isPremiumProviderSanitizerAllowlistEntry(): Boolean =
        trim().matches(Regex(""""api\.(?:top-posters|ratingposterdb)\.com",?$"""))

    private companion object {
        private const val CONTEXT_RADIUS = 3
        private const val RAW_BOUNDARY_TEST_PATH =
            "app/src/test/java/com/nexio/tv/architecture/RawRemoteArtworkUrlBoundaryTest.kt"
        private val expectedSnapshotSanitizerAllowlist = listOf(
            "app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:102" to
                "\"api.ratingposterdb.com\",",
            "app/src/main/java/com/nexio/tv/data/local/HomeCatalogSnapshotStore.kt:103" to
                "\"api.top-posters.com\""
        )
        private val expectedSnapshotSanitizerPrefixLocations =
            expectedSnapshotSanitizerAllowlist.map { it.first }

        private val rawUrlLiteralRegex = Regex("""["']https?://""")
        private val premiumProviderUrlLiteralRegex = Regex(
            """https://api\.(?:top-posters|ratingposterdb)\.com"""
        )
        private val localRawUrlVariableRegex = Regex("""\b(?:val|var)\s+([A-Za-z_][A-Za-z0-9_]*)\s*=\s*["']https?://""")
        private val providerDomainLiteralRegex = Regex(
            """["'][^"']*https://(?:image\.tmdb\.org|artworks\.thetvdb\.com|media\.kitsu\.io|api\.ratingposterdb\.com|api\.top-posters\.com)[^"']*["']"""
        )
        private val dataCallHttpLiteralRegex = Regex("""\.data\s*\(\s*["']https?://""")
        private val dataCallRegex = Regex("""\.data\s*\(""")
        private val dataCallRawUrlVariableRegex = Regex("""\.data\s*\(\s*[A-Za-z_][A-Za-z0-9_]*\s*\)""")
        private val rawArtworkFieldReferenceRegex = Regex(
            """\b(?:progress|nextUp|info|entry|playbackInfo)(?:\.[A-Za-z_][A-Za-z0-9_]*)*\.(?:poster|background|backdrop|logo|thumbnail)\b|\bitem\.(?!(?:heroPreview|metaPreview|displayMetadata|displayPoster|displayBackground|displayBackdrop|displayLogo|displayThumbnail)\b)(?:[A-Za-z_][A-Za-z0-9_]*\.)*(?:poster|background|backdrop|logo|thumbnail)\b"""
        )
        private val localArtworkModelVariableRegex = Regex(
            """\b(?:val|var)\s+((?:image|poster|backdrop|background|logo|thumbnail|stable)[A-Za-z0-9_]*)\s*=""",
            RegexOption.IGNORE_CASE
        )
        private val imageModelContextRegex = Regex(
            """\bAsyncImage\s*\(|\brememberAsyncImagePainter\s*\(|\bImageRequest\.Builder\s*\(|\.data\s*\(""",
            RegexOption.MULTILINE
        )
        private val finalArtworkContextRegex = Regex(
            """\b(?:poster|backdrop|background|logo|thumbnail|imageModel|imageUrl|stableBackdrop)\b\s*=""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
        )
        private val legacyPosterRatingsApplyRegex = Regex("""posterRatingsUrlResolver\.apply\s*\(""")

        private fun variableReferenceRegex(variable: String): Regex {
            return Regex("""\b${Regex.escape(variable)}\b""")
        }
    }
}
