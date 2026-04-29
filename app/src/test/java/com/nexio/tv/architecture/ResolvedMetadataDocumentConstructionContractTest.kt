package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F2-J-03 pin: ResolvedMetadataDocument MUST be constructed only by FieldResolver and
 * MetadataRouterFacade. Any third construction site bypasses field-ownership provenance
 * and undermines the canonical metadata flow.
 */
class ResolvedMetadataDocumentConstructionContractTest {

    @Test
    fun `ResolvedMetadataDocument is constructed only by FieldResolver and MetadataRouterFacade`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv")

        val allowedRelativePaths = setOf(
            "core/metadata/router/FieldResolver.kt",
            "core/metadata/router/MetadataRouterFacade.kt"
        )

        // Match `ResolvedMetadataDocument(` (constructor call) — not `ResolvedMetadataDocument` as type ref
        // and not `(data )class ResolvedMetadataDocument(` (the class declaration in MetadataModels.kt).
        // We scan line-by-line, skipping lines that declare the class itself.
        val pattern = Regex("\\bResolvedMetadataDocument\\s*\\(")
        val declarationPattern = Regex("\\bclass\\s+ResolvedMetadataDocument\\s*\\(")

        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(productionRoot).path
                if (rel in allowedRelativePaths) return@filter false
                f.readLines().any { line ->
                    pattern.containsMatchIn(line) && !declarationPattern.containsMatchIn(line)
                }
            }
            .map { it.relativeTo(productionRoot).path }
            .toList()

        assertEquals(
            "F2-J-03: ResolvedMetadataDocument may only be constructed by FieldResolver " +
                "and MetadataRouterFacade. Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }
}
