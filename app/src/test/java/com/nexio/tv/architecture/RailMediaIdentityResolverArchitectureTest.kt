package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RailMediaIdentityResolverArchitectureTest {
    @Test
    fun `resolver documents temporary compatibility adapter boundaries`() {
        val source = requiredFile(
            "app/src/main/java/com/nexio/tv/core/integration/RailMediaIdentityResolver.kt"
        ).readText()
        val resolverKdoc = kdocImmediatelyBeforeClass(source, "RailMediaIdentityResolver")

        assertNotNull(
            "RailMediaIdentityResolver must document its temporary cache-key compatibility role " +
                "and expiration before StableIdBundleResolver migration.",
            resolverKdoc
        )
        assertResolverKdocSemantics(resolverKdoc!!)
    }

    @Test
    fun `resolver kdoc scanner ignores matching text not attached to resolver class`() {
        val source = """
            package com.nexio.tv.fixture

            $SEMANTIC_KDOC_FIXTURE
            class CopiedDocumentation

            class RailMediaIdentityResolver
        """.trimIndent()

        val resolverKdoc = kdocImmediatelyBeforeClass(source, "RailMediaIdentityResolver")

        assertNull(
            "RailMediaIdentityResolver KDoc guard must inspect only the KDoc immediately before the resolver class.",
            resolverKdoc
        )
    }

    @Test
    fun `resolver kdoc semantics tolerate harmless line wrapping`() {
        val kdoc = """
            /**
             * Temporary compatibility adapter for rail cache ownership keys.
             *
             * Canonical identity ownership belongs to StableIdBundleResolver. This adapter may
             * normalize already-observed rail identifiers for cache key stability only.
             *
             * It must not perform network identity bridging and must not be injected into UI,
             * ViewModel, player, or screensaver code.
             *
             * Expiration: remove after MetaPreview / RailItemPreview carry StableIdBundle directly.
             */
        """.trimIndent()

        assertResolverKdocSemantics(kdoc)
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

    private fun kdocImmediatelyBeforeClass(source: String, className: String): String? =
        Regex(
            pattern = """(/\*\*.*?\*/)\s*(?:@[A-Za-z_][A-Za-z0-9_]*(?:\([^)]*\))?\s*)*class\s+$className\b""",
            option = RegexOption.DOT_MATCHES_ALL
        ).find(source)?.groupValues?.getOrNull(1)

    private fun assertResolverKdocSemantics(kdoc: String) {
        val normalized = kdoc
            .lineSequence()
            .map { line ->
                line.trim()
                    .removePrefix("/**")
                    .removePrefix("*/")
                    .removePrefix("*")
                    .trim()
            }
            .joinToString(separator = " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

        val requiredSemantics = listOf(
            "temporary compatibility adapter role" to
                Regex("""temporary\s+compatibility\s+adapter""", RegexOption.IGNORE_CASE),
            "canonical ownership belongs to StableIdBundleResolver" to
                Regex("""canonical\s+identity\s+ownership\s+belongs\s+to\s+StableIdBundleResolver""", RegexOption.IGNORE_CASE),
            "already-observed rail identifier normalization for cache key stability only" to
                Regex("""may\s+normalize\s+already-observed\s+rail\s+identifiers\s+for\s+cache\s+key\s+stability\s+only""", RegexOption.IGNORE_CASE),
            "network identity bridging prohibition" to
                Regex("""must\s+not\s+perform\s+network\s+identity\s+bridging""", RegexOption.IGNORE_CASE),
            "UI, ViewModel, player, and screensaver injection prohibition" to
                Regex("""must\s+not\s+be\s+injected\s+into\s+UI,\s+ViewModel,\s+player,\s+or\s+screensaver\s+code""", RegexOption.IGNORE_CASE),
            "expiration after previews carry StableIdBundle directly" to
                Regex("""expiration:.*after.*MetaPreview\s*/\s*RailItemPreview.*carry\s+StableIdBundle\s+directly""", RegexOption.IGNORE_CASE)
        )

        requiredSemantics.forEach { (label, pattern) ->
            assertTrue(
                "RailMediaIdentityResolver KDoc is missing required semantic: $label. KDoc: $normalized",
                pattern.containsMatchIn(normalized)
            )
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

        private val SEMANTIC_KDOC_FIXTURE = """
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
