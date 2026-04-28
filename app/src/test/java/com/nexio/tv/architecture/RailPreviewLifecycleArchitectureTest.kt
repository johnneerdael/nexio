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
        val functionBody = functionSource(content, "railPreviewsToCatalogRow")
        val normalized = functionBody.replace(Regex("""\s+"""), " ")
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
            Regex("""\b${Regex.escape(renderer)}\b""").containsMatchIn(functionBody)
        }
        if (offenders.isNotEmpty()) {
            fail("railPreviewsToCatalogRow must not grow provider-specific render methods: $offenders")
        }
    }

    @Test
    fun `built in rail preview mappers are used by production rail assembly`() {
        val productionSources = productionKotlinFiles()
            .filterNot { file -> file.invariantSeparatorsPath.contains("/data/integration/railpreview/") }
            .joinToString(separator = "\n") { file -> file.readText() }

        val requiredMapperUsages = mapOf(
            "TraktDiscoveryService.kt" to "TraktRailPreviewMapper",
            "MDBListDiscoveryService.kt" to "MDBListRailPreviewMapper",
            "TmdbDiscoveryService.kt" to "TmdbRailPreviewMapper",
            "KitsuDiscoveryService.kt" to "KitsuRailPreviewMapper",
            "SimklDiscoveryService.kt" to "SimklRailPreviewMapper"
        )

        val missing = requiredMapperUsages.filterNot { (fileName, mapperName) ->
            val source = productionKotlinFiles().firstOrNull { it.name == fileName }?.readText().orEmpty()
            source.contains(mapperName) && source.contains("toMetaPreview()")
        }

        if (missing.isNotEmpty()) {
            fail(
                "Built-in rail providers must use source-specific RailPreviewMapper classes before " +
                    "entering the shared MetaPreview lifecycle. Missing production usages: " +
                    missing.entries.joinToString { (fileName, mapperName) -> "$mapperName in $fileName" }
            )
        }
    }

    @Test
    fun `home production code has no rail preview hydration sidecar`() {
        val forbiddenPatterns = mapOf(
            "RailPreviewHydration*" to Regex("""\bRailPreviewHydration\w*\b"""),
            "RailPreviewHydrator*" to Regex("""\bRailPreviewHydrator\w*\b"""),
            "scheduleRailPreviewHydration" to Regex("""\bscheduleRailPreviewHydration\b"""),
            "hydrateRailPreview" to Regex("""\bhydrateRailPreview\b"""),
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

    @Test
    fun `home first paint tracing happens at card construction not hydration request construction`() {
        val modernHomeModels = homeFile("ModernHomeModels.kt").readText()
        val buildCatalogItem = functionSource(modernHomeModels, "buildCatalogItem")
        assertTrue(
            "buildCatalogItem is the canonical Home card construction boundary and must emit first-paint tracing.",
            buildCatalogItem.contains("item.toFirstPaintHomeDisplayMetadata()")
        )

        val presentationPipeline = homeFile("HomeViewModelPresentationPipeline.kt").readText()
        val fetchProviderEnrichment = functionSource(presentationPipeline, "fetchProviderEnrichmentForPreview")
        assertTrue(
            "metadata hydration request construction must use pure addon metadata and must not emit first-paint tracing.",
            Regex("""addonMetadata\s*=\s*item\.toHomeDisplayMetadata\(\)""")
                .containsMatchIn(fetchProviderEnrichment)
        )
        if (fetchProviderEnrichment.contains("toFirstPaintHomeDisplayMetadata")) {
            fail("metadata hydration request construction must not call toFirstPaintHomeDisplayMetadata.")
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

    private fun productionKotlinFiles(): List<File> {
        val root = File("app/src/main/java/com/nexio/tv")
        assertTrue("Required production source directory is missing: ${root.path}", root.isDirectory)
        return root.walkTopDown()
            .filter { file -> file.isFile && file.extension == "kt" }
            .toList()
    }

    private fun functionSource(content: String, functionName: String): String {
        val functionMatch = Regex(
            """(?m)^(?:internal|private|public)?\s*(?:suspend\s+)?fun\s+(?:[\w.<>]+\.)?${Regex.escape(functionName)}\s*\("""
        ).find(content)
        if (functionMatch == null) {
            fail("Required function is missing: $functionName")
            throw AssertionError("unreachable")
        }

        val functionStart = content.lastIndexOf('\n', functionMatch.range.first)
            .let { index -> if (index == -1) 0 else index + 1 }
        val expressionBodyStart = content.indexOf('=', functionMatch.range.last)
        val bodyStart = content.indexOf('{', functionMatch.range.first)

        if (expressionBodyStart != -1 && (bodyStart == -1 || expressionBodyStart < bodyStart)) {
            val nextDeclaration = Regex("""\n(?:internal|private|public)?\s*(?:fun|class|data\s+class|object)\b""")
                .find(content, expressionBodyStart + 1)
            return content.substring(functionStart, nextDeclaration?.range?.first ?: content.length)
        }

        if (bodyStart == -1) {
            fail("Required function must have a block body: $functionName")
            throw AssertionError("unreachable")
        }

        var depth = 0
        for (index in bodyStart until content.length) {
            when (content[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return content.substring(functionStart, index + 1)
                    }
                }
            }
        }

        fail("Required function body is unterminated: $functionName")
        throw AssertionError("unreachable")
    }

    private companion object {
        private val metaPreviewImportPattern = Regex(
            """^import\s+com\.nexio\.tv\.domain\.model\.MetaPreview\b.*$""",
            RegexOption.MULTILINE
        )
    }
}
