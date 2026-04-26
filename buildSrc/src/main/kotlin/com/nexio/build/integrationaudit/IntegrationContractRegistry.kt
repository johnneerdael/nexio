package com.nexio.build.integrationaudit

import java.io.File

class IntegrationContractRegistry(
    private val projectRoot: File
) {
    fun expectedApiShapes(expectedShapesFile: File): Map<String, Map<String, String>> =
        extractExpectedApiShapes(expectedShapesFile)

    fun integrationContractShapes(expectedContractsFile: File): Map<String, Map<String, String>> =
        extractIntegrationContractShapes(expectedContractsFile)

    fun integrationCacheContracts(expectedContractsFile: File): Map<String, Map<String, String>> =
        extractIntegrationCacheContracts(expectedContractsFile)

    fun bestPracticeRuleSupport(expectedContractsFile: File): Map<String, BestPracticeRuleSupport> =
        extractBestPracticeRuleSupport(expectedContractsFile)

    fun providerContractProvenanceRows(
        expectedContractsFile: File,
        contractShapes: Map<String, Map<String, String>>
    ): List<IntegrationProviderContractProvenanceRow> =
        buildProviderContractProvenanceRows(expectedContractsFile, contractShapes)

    fun providerContractSourceExists(sourceFile: String): Boolean {
        if (sourceFile.isBlank() || sourceFile.startsWith("/")) return false

        val root = projectRoot.canonicalFile
        val resolved = root.resolve(sourceFile).canonicalFile
        return resolved.path.startsWith(root.path + File.separator) && resolved.isFile
    }

    private fun extractBestPracticeRuleSupport(file: File): Map<String, BestPracticeRuleSupport> {
        if (!file.exists()) return emptyMap()
        val block = file.readText()
            .substringAfter("bestPracticeRuleSupport:", "")
            .substringBefore("contractSources:", "")
        return Regex("""(?m)^  ([a-z0-9-]+):\s*(EXECUTABLE|DECLARED_ONLY)\s*$""")
            .findAll(block)
            .associate { match ->
                match.groupValues[1] to BestPracticeRuleSupport.valueOf(match.groupValues[2])
            }
    }
}
