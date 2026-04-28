package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RailPreviewLifecycleArchitectureTest {
    private val homeDirectory = File("app/src/main/java/com/nexio/tv/ui/screens/home")

    @Test
    fun `home renderers depend on shared meta previews not rail provider shapes`() {
        val rendererFiles = listOf(
            homeFile("ModernHomeRows.kt"),
            homeFile("ModernHomeContent.kt"),
            homeFile("HomeScreen.kt")
        )
        val forbiddenImports = listOf(
            Regex("""^import\s+com\.nexio\.tv\.data\.integration\.railpreview\..+$""", RegexOption.MULTILINE),
            Regex("""^import\s+com\.nexio\.tv\.data\.remote\.dto\..+$""", RegexOption.MULTILINE),
            Regex("""^import\s+com\.nexio\.tv\.domain\.model\.RailItemPreview\b.*$""", RegexOption.MULTILINE)
        )

        val offenders = rendererFiles.flatMap { file ->
            val content = file.readText()
            val missingSharedPreview = if (!metaPreviewImportPattern.containsMatchIn(content)) {
                listOf("${file.invariantSeparatorsPath}:missing MetaPreview import")
            } else {
                emptyList()
            }
            val forbiddenMatches = forbiddenImports.flatMap { pattern ->
                pattern.findAll(content).map { match ->
                    "${file.invariantSeparatorsPath}:${match.value.trim()}"
                }
            }
            missingSharedPreview + forbiddenMatches
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Home renderer files must render shared MetaPreview values and must not import " +
                    "rail preview mappers, RailItemPreview, or provider DTOs:\n${offenders.joinToString("\n")}"
            )
        }
    }

    @Test
    fun `rail preview catalog row bridge maps previews into catalog row meta previews`() {
        val content = homeFile("HomeViewModelCatalogUtils.kt").readText()
        val normalized = content.replace(Regex("""\s+"""), " ")
        val forbiddenRenderers = listOf(
            "renderTrakt",
            "renderMDBList",
            "renderSimkl",
            "renderKitsu",
            "renderTmdb"
        )

        assertTrue(
            "railPreviewsToCatalogRow must enter CatalogRow through shared MetaPreview conversion.",
            normalized.contains("items = previews.map { it.toMetaPreview() }")
        )

        val offenders = forbiddenRenderers.filter { renderer ->
            Regex("""\b${Regex.escape(renderer)}\b""").containsMatchIn(content)
        }
        if (offenders.isNotEmpty()) {
            fail("railPreviewsToCatalogRow must not grow provider-specific render methods: $offenders")
        }
    }

    @Test
    fun `home production code has no rail preview hydration sidecar`() {
        val forbiddenPatterns = mapOf(
            "object RailPreviewHydrationCoordinator" to Regex("""\bobject\s+RailPreviewHydrationCoordinator\b"""),
            "providerForVisibleHydration" to Regex("""\bproviderForVisibleHydration\b""")
        )

        val offenders = homeProductionFiles().flatMap { file ->
            val content = file.readText()
            forbiddenPatterns.filterValues { pattern -> pattern.containsMatchIn(content) }
                .keys
                .map { patternName -> "${file.invariantSeparatorsPath}:$patternName" }
        }

        if (offenders.isNotEmpty()) {
            fail("Home production code must not carry a parallel rail hydration track:\n${offenders.joinToString("\n")}")
        }
    }

    @Test
    fun `home first paint mapper forwards source provenance to tracer`() {
        val content = homeFile("HomeFirstPaintMetadataMapper.kt").readText()
        val forbiddenHardcodedAddonSource = listOf(
            Regex("source\\s*=\\s*\"ADDON_META_PREVIEW\""),
            Regex("""source\s*=\s*FirstPaintSource\.ADDON_META_PREVIEW\.name""")
        )

        assertTrue(
            "Home first-paint tracing must use the MetaPreview provenance source.",
            content.contains("source = firstPaintSource.name")
        )

        val offenders = forbiddenHardcodedAddonSource.filter { pattern -> pattern.containsMatchIn(content) }
        if (offenders.isNotEmpty()) {
            fail("Home first-paint tracing must not hardcode addon provenance at the Home boundary.")
        }
    }

    private fun homeFile(name: String): File = File(homeDirectory, name).also { file ->
        assertTrue("Required Home source file is missing: ${file.path}", file.isFile)
    }

    private fun homeProductionFiles(): List<File> {
        assertTrue("Required Home source directory is missing: ${homeDirectory.path}", homeDirectory.isDirectory)
        return homeDirectory.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
    }

    private companion object {
        private val metaPreviewImportPattern = Regex(
            """^import\s+com\.nexio\.tv\.domain\.model\.MetaPreview\b.*$""",
            RegexOption.MULTILINE
        )
    }
}
