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
            normalized.contains("CatalogRow(") &&
                normalized.contains("items =") &&
                normalized.contains("previews.map") &&
                normalized.contains("toMetaPreview()")
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
        val requiredMapperUsages = mapOf(
            "TraktDiscoveryService.kt" to "TraktRailPreviewMapper",
            "MDBListDiscoveryService.kt" to "MDBListRailPreviewMapper",
            "TmdbDiscoveryService.kt" to "TmdbRailPreviewMapper",
            "KitsuDiscoveryService.kt" to "KitsuRailPreviewMapper",
            "SimklDiscoveryService.kt" to "SimklRailPreviewMapper"
        )

        val missing = requiredMapperUsages.filterNot { (fileName, mapperName) ->
            val source = productionKotlinFiles().firstOrNull { it.name == fileName }?.readText().orEmpty()
            source.contains(mapperName) &&
                (source.contains("RailItemPreview") || source.contains("RailPreviewCatalogRowRecord"))
        }

        if (missing.isNotEmpty()) {
            fail(
                "Built-in rail providers must use source-specific RailPreviewMapper classes and retain " +
                    "RailItemPreview or RailPreviewCatalogRowRecord records before CatalogRow publication. Missing production usages: " +
                    missing.entries.joinToString { (fileName, mapperName) -> "$mapperName in $fileName" }
            )
        }
    }

    @Test
    fun `built in discovery snapshots store rail preview source records`() {
        val checkedFiles = listOf(
            "data/repository/TraktDiscoveryService.kt",
            "data/local/TraktDiscoverySnapshotStore.kt",
            "data/repository/MDBListDiscoveryService.kt",
            "data/local/MDBListDiscoverySnapshotStore.kt",
            "data/repository/SimklDiscoveryService.kt",
            "data/local/SimklDiscoverySnapshotStore.kt",
            "data/repository/TmdbDiscoveryModels.kt",
            "data/repository/KitsuDiscoveryModels.kt"
        )
        val forbiddenStorageFields = listOf(
            Regex("""val\s+\w+\s*:\s*List<MetaPreview>"""),
            Regex("""val\s+\w+\s*:\s*Map<String,\s*List<MetaPreview>>"""),
            Regex("""val\s+\w+\s*:\s*Map<String,\s*CatalogRow>""")
        )

        val offenders = checkedFiles.flatMap { relativePath ->
            val source = productionFile(relativePath).readText()
                .replace(Regex("""constructor\([\s\S]*?\)\s*:\s*this\([\s\S]*?\)"""), "")
            forbiddenStorageFields.flatMap { pattern ->
                pattern.findAll(source).map { match ->
                    "app/src/main/java/com/nexio/tv/$relativePath:${match.value}"
                }
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Built-in discovery snapshots and stores must retain RailItemPreview or " +
                    "RailPreviewCatalogRowRecord source records, not MetaPreview/CatalogRow storage fields:\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `kitsu discovery service retains mapper result until catalog row publication`() {
        val source = productionFile("data/repository/KitsuDiscoveryService.kt").readText()

        assertTrue(
            "Kitsu catalog result mapping must call KitsuRailPreviewMapper.mapAnime, retain " +
                "RailItemPreview values, and publish through RailPreviewCatalogRowRecord.",
            source.contains("railPreviewMapper.mapAnime(") &&
                source.contains("RailPreviewCatalogRowRecord(") &&
                source.contains("previews =")
        )
    }

    @Test
    fun `kitsu discovery service does not apply post bridge display corrections`() {
        val source = productionFile("data/repository/KitsuDiscoveryService.kt").readText()
        val forbiddenPatterns = listOf(
            "toMetaPreview().copy(",
            ".toMetaPreview().copy("
        )
        val offenders = forbiddenPatterns.filter { pattern -> source.contains(pattern) }

        if (offenders.isNotEmpty()) {
            fail(
                "Kitsu display corrections must live in KitsuRailPreviewMapper and flow through " +
                    "the shared RailItemPreview.toMetaPreview() bridge, not service-level copies: $offenders"
            )
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

    @Test
    fun `dead home preview hydration planner is removed`() {
        val planner = homeFileOrNull("HomePreviewHydrationPlanner.kt")

        if (planner != null) {
            fail("HomePreviewHydrationPlanner is dead production architecture and must not exist: ${planner.path}")
        }
    }

    @Test
    fun `home production does not contain rail preview sidecar scheduler names`() {
        val forbiddenNames = listOf(
            "HomePreviewHydrationPlanner",
            "RailPreviewHydrationPlanner",
            "RailPreviewHydrationScheduler",
            "HomeRailPreviewHydrationScheduler"
        )
        val offenders = homeProductionFiles().flatMap { file ->
            val source = file.readText()
            forbiddenNames.filter { name -> source.contains(name) }
                .map { name -> "${file.invariantSeparatorsPath}:$name" }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Home production must not grow a rail-preview-specific hydration planner/scheduler:\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `home production does not manually construct metadata router sidecars`() {
        val forbiddenConstructors = listOf(
            Regex("""\bMetadataRouterFacade\s*\("""),
            Regex("""\bFieldResolver\s*\(""")
        )
        val offenders = homeProductionFiles().flatMap { file ->
            val source = file.readText()
            forbiddenConstructors.flatMap { pattern ->
                pattern.findAll(source).map { match ->
                    "${file.invariantSeparatorsPath}:${match.value.trim()}"
                }
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "Home production must use injected/canonical metadata routing dependencies and must not " +
                    "manually construct MetadataRouterFacade or FieldResolver fallback sidecars:\n" +
                    offenders.joinToString("\n")
            )
        }
    }

    @Test
    fun `home visible focused enrichment keeps source neutral meta preview entrypoint`() {
        val source = homeFile("HomeViewModelPresentationPipeline.kt").readText()
        val fetchProviderEnrichment = functionSource(source, "fetchProviderEnrichmentForPreview")

        assertTrue(
            "Visible/focused Home enrichment must keep using the source-neutral MetaPreview entrypoint.",
            Regex("""fetchProviderEnrichmentForPreview\s*\(\s*item\s*:\s*MetaPreview\s*\)""")
                .containsMatchIn(fetchProviderEnrichment)
        )
    }

    @Test
    fun `metadata audit rail visible scenarios use router facade not synthesized route records`() {
        val source = File("app/src/test/java/com/nexio/tv/metadata/audit/MetadataAuditRunner.kt").readText()
        val railScenario = functionSource(source, "runRailScenario")
        val forbiddenRailSynthesis = listOf(
            Regex("""\brailRoute\s*\("""),
            Regex("""\brailFields\s*\("""),
            Regex("""RuntimeCallEvent\s*\("""),
            Regex("""CacheDecisionEvent\s*\("""),
            Regex("""routingContentId\s*\("""),
            Regex("""mapOf\s*\(\s*metaPreview\.id\s+to\s+targetId\s*\)""")
        )
        val offenders = forbiddenRailSynthesis.flatMap { pattern ->
            pattern.findAll(railScenario).map { match -> match.value.trim() }
        }

        assertTrue(
            "Rail visible hydration audit must execute the shared MetadataRouterFacade.resolveRequest(...) path.",
            railScenario.contains("facade.resolveRequest(")
        )
        assertTrue(
            "Rail visible hydration audit must pass rail previews as RAIL_PREVIEW source context.",
            railScenario.contains("previewSourceRole = com.nexio.tv.core.metadata.router.SourceRole.RAIL_PREVIEW")
        )
        assertTrue(
            "Rail visible hydration audit must request the facade with the source rail item id, not a preselected target provider id.",
            railScenario.contains("contentId = spec.itemId")
        )
        if (offenders.isNotEmpty()) {
            fail(
                "Rail scenario implementation must not synthesize visible route/runtime/cache/field data " +
                    "with rail-only helpers or direct RuntimeCallEvent/CacheDecisionEvent construction: $offenders"
            )
        }
    }

    private fun homeFile(name: String): File = File(homeDirectory, name).also { file ->
        assertTrue("Required Home source file is missing: ${file.path}", file.isFile)
    }

    private fun homeFileOrNull(name: String): File? =
        File(homeDirectory, name).takeIf { file -> file.isFile }

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

    private fun productionFile(relativePath: String): File {
        val file = File("app/src/main/java/com/nexio/tv", relativePath)
        assertTrue("Required production source file is missing: ${file.path}", file.isFile)
        return file
    }

    private fun functionSource(content: String, functionName: String): String {
        val functionMatch = Regex(
            """(?m)^\s*(?:internal|private|public)?\s*(?:suspend\s+)?fun\s+(?:[\w.<>]+\.)?${Regex.escape(functionName)}\s*\("""
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
