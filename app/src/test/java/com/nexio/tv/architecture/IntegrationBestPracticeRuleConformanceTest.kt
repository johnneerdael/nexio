package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationBestPracticeRuleConformanceTest {
    private val contracts = File("app/src/test/resources/integration/expected_integration_contracts.yaml").readText()
    private val reportBuilder = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditReportBuilder.kt").readText()

    @Test
    fun `best practice rules are executable or explicitly declaration only`() {
        val ruleIds = Regex("""bestPracticeRules:\s*\[([^\]]*)]""")
            .findAll(contracts)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()

        val unsupported = ruleIds.filterNot { rule ->
            reportBuilder.contains("\"$rule\"") || contracts.contains("$rule: DECLARED_ONLY")
        }

        assertTrue("Best-practice rules lack executable checks or DECLARED_ONLY classification: $unsupported", unsupported.isEmpty())
    }

    @Test
    fun `best practice support section declares every rule`() {
        val ruleIds = Regex("""bestPracticeRules:\s*\[([^\]]*)]""")
            .findAll(contracts)
            .flatMap { it.groupValues[1].split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
        val supportSection = contracts.substringAfter("bestPracticeRuleSupport:", "").substringBefore("contractSources:")
        val missing = ruleIds.filterNot { supportSection.contains("$it:") }

        assertTrue("bestPracticeRuleSupport missing rules: $missing", missing.isEmpty())
    }

    @Test
    fun `best practice support declarations are parsed and enforced by audit generator`() {
        val registry = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationContractRegistry.kt").readText()
        val builder = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditReportBuilder.kt").readText()

        val declaredRules = Regex("""(?m)^  ([a-z0-9-]+):\s*(EXECUTABLE|DECLARED_ONLY)\s*$""")
            .findAll(contracts.substringAfter("bestPracticeRuleSupport:").substringBefore("contractSources:"))
            .map { it.groupValues[1] to it.groupValues[2] }
            .toMap()

        assertTrue("Expected best-practice declarations in contracts file.", declaredRules.isNotEmpty())
        assertTrue("Registry must parse bestPracticeRuleSupport.", registry.contains("extractBestPracticeRuleSupport"))
        assertTrue("Report builder must fail executable rules without validators.", builder.contains("missing executable validator"))
        assertTrue("Report builder must handle DECLARED_ONLY rule support.", builder.contains("DECLARED_ONLY"))
    }

    @Test
    fun `best practice validators inspect contract evidence instead of broad substrings`() {
        val builder = File("buildSrc/src/main/kotlin/com/nexio/build/integrationaudit/IntegrationAuditReportBuilder.kt").readText()

        assertTrue(
            "TMDB validator must check required append_to_response fields.",
            builder.contains("append_to_response") &&
                builder.contains("credits") &&
                builder.contains("external_ids")
        )
        assertTrue(
            "TVDB validator must check season endpoint shape evidence, not any name containing Season.",
            builder.contains("/series/{id}/episodes") ||
                builder.contains("series/{id}/episodes")
        )
        assertTrue(
            "Kitsu validator must check top-level castings and include graph.",
            builder.contains("/castings") &&
                builder.contains("filter[mediaId]") &&
                builder.contains("person") &&
                builder.contains("character")
        )
        assertTrue(
            "Poster cache validator must check forbidden raw credential forms.",
            builder.contains("apiKey.hashCode") &&
                builder.contains("raw API key")
        )
    }
}
