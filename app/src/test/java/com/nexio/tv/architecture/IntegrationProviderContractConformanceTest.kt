package com.nexio.tv.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IntegrationProviderContractConformanceTest {
    private val contracts = File("app/src/test/resources/integration/expected_integration_contracts.yaml").readText()
    private val expectedShapes = File("app/src/test/resources/integration/expected_api_shapes.yaml").readText()
    private val integrationSources = File("app/src/main/java").walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.readText().contains("apiShapeId") }
        .associate { it.path.replace(File.separatorChar, '/') to it.readText() }

    private fun apiShapeSection(): String =
        contracts.substringAfter("apiShapes:", missingDelimiterValue = "")

    private fun contractShapeIds(): Set<String> =
        Regex("""(?m)^  ([a-z0-9][a-z0-9_.-]+):\s*$""")
            .findAll(apiShapeSection())
            .map { it.groupValues[1] }
            .toSet()

    private fun expectedShapeIds(): Set<String> =
        Regex("""(?m)^([a-z0-9][a-z0-9_.-]+):\s*$""")
            .findAll(expectedShapes)
            .map { it.groupValues[1] }
            .toSet()

    private fun declaredHeaderPolicyIds(): Set<String> =
        Regex("""(?m)^  ([a-z0-9_-]+):\s*$""")
            .findAll(contracts.substringAfter("headerPolicies:").substringBefore("cacheContracts:"))
            .map { it.groupValues[1] }
            .toSet()

    private fun declaredCacheContractIds(): Set<String> =
        Regex("""(?m)^  ([a-z0-9_-]+):\s*$""")
            .findAll(contracts.substringAfter("cacheContracts:").substringBefore("apiShapes:"))
            .map { it.groupValues[1] }
            .toSet()

    private fun shapeBlocks(): List<Pair<String, String>> =
        Regex("""(?ms)^  ([a-z0-9][a-z0-9_.-]+):\n(.*?)(?=^  [a-z0-9][a-z0-9_.-]+:|\z)""")
            .findAll(apiShapeSection())
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    private fun runtimeLiteralShapeIds(): Set<String> =
        integrationSources.values.flatMap { source ->
            Regex("""apiShapeId\s*=\s*"([a-z0-9_.-]+)"""")
                .findAll(source)
                .map { it.groupValues[1] }
                .toList()
        }.toSet()

    @Test
    fun `every expected endpoint shape has a provider contract row`() {
        val missing = expectedShapeIds() - contractShapeIds()
        assertTrue("Expected endpoint shapes missing provider contract rows: $missing", missing.isEmpty())
    }

    @Test
    fun `runtime literal shape ids are registry backed`() {
        val missing = runtimeLiteralShapeIds() - contractShapeIds()
        assertTrue("Runtime apiShapeIds missing provider contract rows: $missing", missing.isEmpty())
    }

    @Test
    fun `every shape references declared header policy and cache contract`() {
        val headerPolicies = declaredHeaderPolicyIds()
        val cacheContracts = declaredCacheContractIds()
        val missing = shapeBlocks().filter { (_, block) ->
            val headerPolicy = Regex("""(?m)^    headerPolicy:\s*(\S+)""").find(block)?.groupValues?.get(1)
            val cacheContract = Regex("""(?m)^    cacheContract:\s*(\S+)""").find(block)?.groupValues?.get(1)
            headerPolicy !in headerPolicies || cacheContract !in cacheContracts
        }.map { it.first }

        assertTrue("Shapes with undeclared header/cache contracts: $missing", missing.isEmpty())
    }

    @Test
    fun `every active shape declares runtime and cache key contract sections`() {
        val missing = shapeBlocks().filter { (_, block) ->
            val active = block.contains("lifecycleStatus: ACTIVE_REQUIRED") ||
                block.contains("lifecycleStatus: ACTIVE_RUNTIME_COVERED")
            active && (
                !block.contains("runtimePolicy:") ||
                    !block.contains("cacheKeyContract:") ||
                    !block.contains("responseHeaders:") ||
                    !block.contains("bestPracticeRules:")
                )
        }.map { it.first }

        assertTrue("Active shapes missing contract sections: $missing", missing.isEmpty())
    }

    @Test
    fun `cache key contracts forbid raw secret material`() {
        val missing = shapeBlocks().filter { (_, block) ->
            val active = block.contains("lifecycleStatus: ACTIVE_REQUIRED") ||
                block.contains("lifecycleStatus: ACTIVE_RUNTIME_COVERED")
            active && (!block.contains("raw API key") || !block.contains("apiKey.hashCode"))
        }.map { it.first }

        assertTrue("Active shapes must forbid raw credentials and hashCode cache keys: $missing", missing.isEmpty())
    }
}
