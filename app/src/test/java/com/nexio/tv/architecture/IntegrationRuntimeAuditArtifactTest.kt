package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationRuntimeAuditArtifactTest {
    @Test
    fun `audit generator is implemented as build logic instead of embedded app build script`() {
        val appBuildFile = File("app/build.gradle.kts").readText()
        val taskFile = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/GenerateIntegrationRuntimeAuditTask.kt")
        val pluginFile = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditPlugin.kt")

        assertTrue("Expected extracted audit task implementation.", taskFile.isFile)
        assertTrue("Expected audit plugin registration.", pluginFile.isFile)
        assertTrue(
            "app/build.gradle.kts should register or apply audit build logic without embedding scanner internals.",
            appBuildFile.contains("generateIntegrationRuntimeAudit")
        )
        assertTrue(
            "app/build.gradle.kts must not contain the source scanner implementation.",
            !appBuildFile.contains("fun extractIntegrationAuditSpecs(") &&
                !appBuildFile.contains("data class IntegrationAuditSpecRow(") &&
                !appBuildFile.contains("fun writeIntegrationRuntimeAudit(")
        )
    }

    @Test
    fun `provider provenance is resolved only inside the audited checkout`() {
        val taskSource = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/GenerateIntegrationRuntimeAuditTask.kt").readText()
        val registrySource = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationContractRegistry.kt").readText()
        val contracts = File("app/src/test/resources/integration/expected_integration_contracts.yaml").readText()

        assertTrue(
            "Audit provenance must not fall back to parent or sibling checkouts.",
            !taskSource.contains("../..") &&
                !registrySource.contains("../..") &&
                !taskSource.contains("parentFile") &&
                !registrySource.contains("parentFile")
        )

        val missingSources = Regex("sourceFile:\\s*\"([^\"]+)\"")
            .findAll(contracts)
            .map { it.groupValues[1] }
            .filterNot { File(it).isFile }
            .toList()

        assertTrue("All provenance source files must exist in this checkout: $missingSources", missingSources.isEmpty())
    }

    @Test
    fun `audit build logic has focused registry scanner and writer classes`() {
        val task = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/GenerateIntegrationRuntimeAuditTask.kt").readText()
        val registry = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationContractRegistry.kt").readText()
        val scanner = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationSourceScanner.kt").readText()
        val writer = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditReportWriter.kt").readText()

        assertTrue("Contract registry must parse expected API shapes.", registry.contains("class IntegrationContractRegistry") && registry.contains("expectedApiShapes"))
        assertTrue("Source scanner must own runtime spec extraction.", scanner.contains("class IntegrationSourceScanner") && scanner.contains("scanRuntimeSpecs"))
        assertTrue("Report writer must own artifact output.", writer.contains("class IntegrationAuditReportWriter") && writer.contains("writeMarkdownReport") && writer.contains("writeJsonReport"))
        assertTrue("Task must delegate contract parsing.", task.contains("IntegrationContractRegistry("))
        assertTrue("Task must delegate source scanning.", task.contains("IntegrationSourceScanner("))
        assertTrue("Task must delegate report writing.", task.contains("IntegrationAuditReportWriter("))

        assertTrue("Task must not define contract parsing helpers.", !task.contains("fun extractExpectedApiShapes(") && !task.contains("fun extractIntegrationContractShapes("))
        assertTrue("Task must not define source scanning helpers.", !task.contains("fun extractIntegrationAuditSpecs(") && !task.contains("fun extractProviderPolicies("))
        assertTrue("Task must not define report writing helpers.", !task.contains("fun writeIntegrationRuntimeAudit(") && !task.contains("fun writeCsv("))
    }

    @Test
    fun `runtime specs expose auditable endpoint shape id`() {
        listOf(
            "app/src/main/java/com/nexio/tv/core/integration/IntegrationSpec.kt",
            "app/src/main/java/com/nexio/tv/core/integration/IntegrationCallSpec.kt",
            "app/src/main/java/com/nexio/tv/core/integration/IntegrationStreamSpec.kt"
        ).forEach { path ->
            val source = File(path).readText()

            assertTrue("$path must expose mandatory apiShapeId.", source.contains("val apiShapeId: String"))
            assertTrue("$path must reject blank apiShapeId.", source.contains("apiShapeId must not be blank"))
            assertTrue("$path must expose headerPolicyId.", source.contains("val headerPolicyId: String"))
            assertTrue("$path must reject blank headerPolicyId.", source.contains("headerPolicyId must not be blank"))
        }
    }

    @Test
    fun `every endpoint shape declares a header policy`() {
        val registry = File("app/src/test/resources/integration/expected_api_shapes.yaml").readText()
        val blocks = Regex("""(?m)^([a-z0-9][a-z0-9_.-]+):\s*$""")
            .findAll(registry)
            .map { match ->
                val start = match.range.first
                val end = Regex("""(?m)^([a-z0-9][a-z0-9_.-]+):\s*$""")
                    .find(registry, match.range.last + 1)
                    ?.range
                    ?.first
                    ?: registry.length
                match.groupValues[1] to registry.substring(start, end)
            }
            .toList()

        val missing = blocks
            .filterNot { (_, block) -> Regex("""(?m)^  headerPolicy:\s*\S+""").containsMatchIn(block) }
            .map { it.first }

        assertTrue("Endpoint shape registry missing headerPolicy for IDs: $missing", missing.isEmpty())
    }

    @Test
    fun `expected endpoint shape registry contains required baseline ids`() {
        val registry = File("app/src/test/resources/integration/expected_api_shapes.yaml")
        assertTrue("Expected endpoint shape registry is missing.", registry.isFile)
        val shapeIds = registry.readLines()
            .mapNotNull { line ->
                Regex("""^([a-z0-9][a-z0-9_.-]+):\s*$""")
                    .matchEntire(line)
                    ?.groupValues
                    ?.get(1)
            }
            .toSet()

        val requiredShapeIds = setOf(
            "tmdb.movie.core",
            "tmdb.tv.core",
            "tvdb.remoteid.lookup",
            "tvdb.series.extended",
            "kitsu.anime.episodes",
            "kitsu.castings",
            "mdblist.rating.batch",
            "omdb.season.ratings",
            "custom_imdb.title.bulk",
            "trakt.movie.comments",
            "trakt.show.comments",
            "theintrodb.media",
            "aniskip.skip_times",
            "animeskip.graphql",
            "arm.imdb_bridge",
            "arm.ids_bridge",
            "rpdb.key_validation",
            "topposters.key_validation",
            "rpdb.poster_template",
            "topposters.poster_template"
        )

        val missing = requiredShapeIds - shapeIds
        assertTrue("Endpoint shape registry missing required IDs: $missing", missing.isEmpty())
    }

    @Test
    fun `audit separates control-plane verdict from MetadataRouter readiness`() {
        val reportBuilder = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditReportBuilder.kt").readText()

        assertTrue(
            "Audit JSON must expose separate gate verdicts.",
            reportBuilder.contains("\"gates\" to linkedMapOf(") &&
                reportBuilder.contains("\"controlPlane\" to verdict") &&
                reportBuilder.contains("\"metadataRouterReadiness\" to metadataRouterVerdict")
        )
        assertTrue(
            "Endpoint rows must use explicit readiness statuses instead of ambiguous MISSING_RUNTIME_SPEC.",
            reportBuilder.contains("ACTIVE_REQUIRED_MISSING") &&
                reportBuilder.contains("PLANNED_NOT_ACTIVE") &&
                reportBuilder.contains("ACTIVE_RUNTIME_COVERED") &&
                !reportBuilder.contains("\"MISSING_RUNTIME_SPEC\"")
        )
        assertTrue(
            "MetadataRouter readiness CSV must be generated.",
            reportBuilder.contains("metadata-router-readiness.csv")
        )
    }

    @Test
    fun `runtime event sample is not synthesized by static report writer`() {
        val auditTask = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/GenerateIntegrationRuntimeAuditTask.kt").readText()
        val runtimeTest = File("app/src/test/java/com/nexio/tv/core/integration/DefaultIntegrationRuntimeTest.kt").readText()
        val appBuildFile = File("app/build.gradle.kts").readText()

        assertTrue(
            "Runtime tests must write the audit sample JSONL file.",
            runtimeTest.contains("writeRuntimeAuditSample") &&
                runtimeTest.contains("runtime-event-sample.generated.jsonl")
        )
        assertTrue(
            "Audit generator must copy runtime fixture events instead of manufacturing cache/network/backoff phases from static spec rows.",
            !auditTask.contains("sampleAuditEvent(") &&
                !auditTask.contains("\"FRESH_CACHE_HIT\"") &&
                !auditTask.contains("\"NETWORK_START\"")
        )
        assertTrue(
            "Audit generator must use the runtime-test-generated event sample.",
            auditTask.contains("runtimeEventFixtureFile") &&
                appBuildFile.contains("runtime-event-sample.generated.jsonl") &&
                appBuildFile.contains("generateRuntimeEventAuditSample") &&
                appBuildFile.contains("DefaultIntegrationRuntimeTest.generated runtime audit sample uses real phase names and network flags") &&
                !appBuildFile.contains("runtime-event-fixture.jsonl")
        )
        assertTrue(
            "Audit generation must fail on FAIL verdict unless explicitly overridden.",
            appBuildFile.contains("integrationRuntimeAudit.failOnFailVerdict") &&
                appBuildFile.contains("orElse(true)")
        )
    }
}
