package com.nexio.build.integrationaudit

import java.io.File

class IntegrationSourceScanner(
    private val projectRoot: File,
    private val sourceRoot: File
) {
    fun readSources(): List<Pair<File, String>> =
        readIntegrationAuditSources(sourceRoot)

    fun integrationProviders(): List<String> =
        extractIntegrationProviders(sourceRoot.resolve("com/nexio/tv/core/integration/IntegrationProvider.kt"))

    fun policySource(): String =
        sourceRoot.resolve("com/nexio/tv/core/integration/IntegrationPolicyRegistry.kt").readText()

    fun scanRuntimeSpecs(
        sources: List<Pair<File, String>>,
        expectedShapes: Map<String, Map<String, String>>
    ): List<IntegrationAuditSpecRow> =
        extractIntegrationAuditSpecs(
            projectDir = projectRoot,
            sources = sources,
            apiShapeConstants = extractIntegrationApiShapeConstants(sources),
            expectedShapes = expectedShapes
        )

    fun scanBoundaryViolations(sources: List<Pair<File, String>>): List<IntegrationAuditViolation> =
        extractIntegrationAuditViolations(projectRoot, sources)
}
