package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationProviderProvenanceCompletenessTest {
    private val providerSource = File("app/src/main/java/com/nexio/tv/core/integration/IntegrationProvider.kt").readText()
    private val contracts = File("app/src/test/resources/integration/expected_integration_contracts.yaml").readText()

    private fun integrationProviders(): Set<String> =
        providerSource
            .substringAfter("enum class IntegrationProvider")
            .substringAfter("{")
            .substringBefore("}")
            .split(',')
            .map { it.trim().lineSequence().first().trim() }
            .filter { it.matches(Regex("[A-Z][A-Z0-9_]*")) }
            .toSet()

    private fun provenanceProviders(): Set<String> =
        Regex("""(?m)^  ([A-Z][A-Z0-9_]*):\n    lifecycleStatus:""")
            .findAll(contracts.substringAfter("contractSources:").substringBefore("headerPolicies:"))
            .map { it.groupValues[1] }
            .toSet()

    @Test
    fun `every integration provider has provenance or explicit lifecycle classification`() {
        assertEquals(integrationProviders(), provenanceProviders())
    }

    @Test
    fun `contract source paths are repo relative`() {
        val absolutePaths = Regex("""sourceFile:\s*"/""").findAll(contracts).map { it.value }.toList()
        assertTrue("Contract source paths must be repo relative: $absolutePaths", absolutePaths.isEmpty())
    }
}
