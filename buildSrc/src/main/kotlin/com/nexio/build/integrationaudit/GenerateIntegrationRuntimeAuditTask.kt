package com.nexio.build.integrationaudit

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

abstract class GenerateIntegrationRuntimeAuditTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedShapesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expectedContractsFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val metadataRouterPrerequisitesFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val runtimeEventFixtureFile: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Input
    abstract val failOnFailVerdict: Property<Boolean>

    @TaskAction
    fun generate() {
        val projectRootFile = projectRoot.asFile.get()
        val mainJavaDir = sourceRoot.asFile.get()
        val expectedShapes = expectedShapesFile.asFile.get()
        val expectedContracts = expectedContractsFile.asFile.get()
        val metadataRouterPrerequisites = metadataRouterPrerequisitesFile.asFile.get()
        val runtimeEventFixture = runtimeEventFixtureFile.asFile.get()
        val outputDir = outputDirectory.asFile.get()
        val contractRegistry = IntegrationContractRegistry(projectRootFile)
        val scanner = IntegrationSourceScanner(projectRootFile, mainJavaDir)
        val sources = scanner.readSources()
        val providers = scanner.integrationProviders()
        val policySource = scanner.policySource()
        val shapeContracts = contractRegistry.expectedApiShapes(expectedShapes)
        val integrationContractShapes = contractRegistry.integrationContractShapes(expectedContracts)
        val integrationCacheContracts = contractRegistry.integrationCacheContracts(expectedContracts)
        val metadataRouterPrerequisiteShapeIds = readMetadataRouterPrerequisiteShapeIds(metadataRouterPrerequisites)
        val bestPracticeRuleSupport = contractRegistry.bestPracticeRuleSupport(expectedContracts)
        val providerContractRows = contractRegistry.providerContractProvenanceRows(expectedContracts, integrationContractShapes)
        providerContractRows.forEach { row ->
            check(contractRegistry.providerContractSourceExists(row.sourceFile)) {
                "Provider contract source does not exist for ${row.provider}: ${row.sourceFile}"
            }
        }
        val specRows = scanner.scanRuntimeSpecs(sources, shapeContracts)
        val detectedViolations = scanner.scanBoundaryViolations(sources)
        val exemptions = documentedIntegrationAuditExemptions(detectedViolations)
        val violations = detectedViolations.filterNot { violation ->
            violation.forbiddenDependency == "Coil remote model"
        }
        val providerRows = buildIntegrationAuditProviderRows(
            providers = providers,
            policySource = policySource,
            specRows = specRows,
            violations = violations,
            sources = sources
        )
        val writer = IntegrationAuditReportWriter(outputDir)
        writer.writeMarkdownReport(
            runtimeEventFixtureFile = runtimeEventFixture,
            providers = providerRows,
            specs = specRows,
            violations = violations,
            exemptions = exemptions,
            expectedShapes = shapeContracts,
            contractShapes = integrationContractShapes,
            cacheContracts = integrationCacheContracts,
            metadataRouterPrerequisiteShapeIds = metadataRouterPrerequisiteShapeIds,
            bestPracticeRuleSupport = bestPracticeRuleSupport,
            providerContractRows = providerContractRows,
            gitSha = currentGitSha(projectRootFile),
            gitWorktreeState = currentGitWorktreeState(projectRootFile)
        )
        validateIntegrationRuntimeAuditOutput(
            outputDir = outputDir,
            failOnFailVerdict = failOnFailVerdict.get()
        )
        println("Generated ${outputDir.relativeTo(projectRootFile)}")
    }
}
