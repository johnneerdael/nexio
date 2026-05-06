package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RailMediaIdentityResolverArchitectureTest {
    @Test
    fun `resolver documents temporary compatibility adapter boundaries`() {
        val source = requiredFile(
            "app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt"
        ).readText()

        assertTrue(
            "RailMediaIdentityResolver must document its temporary cache-key compatibility role " +
                "and expiration before StableIdBundleResolver migration.",
            REQUIRED_KDOC in source
        )
    }

    @Test
    fun `resolver imports stay inside approved integration and local boundaries`() {
        val offenders = productionKotlinFilesUnder("app/src/main/java/com/nexio/tv")
            .filterNot { file -> isApprovedImportBoundary(file) }
            .flatMap { file -> resolverImportOffenders(file) }

        failIfNotEmpty(
            offenders,
            "RailMediaIdentityResolver is a temporary rail cache ownership adapter owned by " +
                "core/data integration and local persistence. Temporary exception: " +
                "data/repository/ContinueWatchingSnapshotService.kt may import it only as the " +
                "Continue Watching snapshot persistence owner for existing rail cache ownership " +
                "keys; expiration is the StableIdBundleResolver migration where " +
                "MetaPreview/RailItemPreview carry StableIdBundle directly. Move other production " +
                "imports behind an approved local/integration boundary before using it from UI, " +
                "ViewModel, player, or screensaver code."
        )
    }

    @Test
    fun `resolver import scanner catches exact aliased and wildcard imports`() {
        val exact = kotlinFixture("import com.nexio.tv.core.integration.RailMediaIdentityResolver")
        val aliased = kotlinFixture("import com.nexio.tv.core.integration.RailMediaIdentityResolver as RailResolver")
        val wildcard = kotlinFixture("import com.nexio.tv.core.integration.*")

        val offenders = listOf(exact, aliased, wildcard).flatMap(::resolverImportOffenders)

        assertTrue(
            "RailMediaIdentityResolver boundary scanner must catch exact, aliased, and wildcard imports: $offenders",
            offenders.size == 3
        )
    }

    private fun resolverImportOffenders(file: File): List<String> =
        file.readLines().mapIndexedNotNull { index, line ->
            if (RESOLVER_IMPORT.containsMatchIn(line)) {
                "${file.invariantSeparatorsPath}:${index + 1}:RailMediaIdentityResolver import"
            } else {
                null
            }
        }

    private fun isApprovedImportBoundary(file: File): Boolean {
        val path = file.invariantSeparatorsPath
        return path.startsWith("app/src/main/java/com/nexio/tv/core/integration/") ||
            path.startsWith("app/src/main/java/com/nexio/tv/data/integration/") ||
            path.startsWith("app/src/main/java/com/nexio/tv/data/local/") ||
            path == "app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt"
    }

    private fun productionKotlinFilesUnder(path: String): List<File> {
        val root = requiredDirectory(path)
        return root.walkTopDown()
            .filter { file -> file.isFile && (file.extension == "kt" || file.extension == "java") }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
    }

    private fun requiredFile(path: String): File {
        val file = File(path)
        require(file.isFile) { "Required architecture target is missing: $path" }
        return file
    }

    private fun requiredDirectory(path: String): File {
        val file = File(path)
        require(file.isDirectory) { "Required architecture target directory is missing: $path" }
        return file
    }

    private fun failIfNotEmpty(offenders: List<String>, message: String) {
        if (offenders.isNotEmpty()) {
            fail("$message Offenders:\n${offenders.joinToString(separator = "\n")}")
        }
    }

    private fun kotlinFixture(source: String): File =
        File.createTempFile("RailMediaIdentityResolverArchitectureTest", ".kt").apply {
            writeText(source)
            deleteOnExit()
        }

    private companion object {
        private val RESOLVER_IMPORT = Regex(
            """^\s*import\s+com\.nexio\.tv\.core\.integration\.(?:RailMediaIdentityResolver(?:\s+as\s+\w+)?|\*)\s*$"""
        )

        private val REQUIRED_KDOC = """
            /**
             * Temporary compatibility adapter for rail cache ownership keys.
             *
             * Canonical identity ownership belongs to StableIdBundleResolver. This adapter may normalize
             * already-observed rail identifiers for cache key stability only. It must not perform network
             * identity bridging and must not be injected into UI, ViewModel, player, or screensaver code.
             *
             * Expiration: remove after MetaPreview/RailItemPreview carry StableIdBundle directly.
             */
        """.trimIndent()
    }
}
