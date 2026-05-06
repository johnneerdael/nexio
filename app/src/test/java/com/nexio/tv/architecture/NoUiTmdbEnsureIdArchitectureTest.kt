package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.fail
import org.junit.Test

class NoUiTmdbEnsureIdArchitectureTest {
    @Test
    fun `ui and main activity must not call tmdb identity bridge helpers`() {
        val offenders = scanFiles(
            files = productionKotlinFilesUnder("app/src/main/java/com/nexio/tv/ui") +
                requiredFile("app/src/main/java/com/nexio/tv/MainActivity.kt"),
            forbiddenPatterns = BRIDGE_CALL_PATTERNS
        )

        failIfNotEmpty(
            offenders,
            "UI and MainActivity must consume stable IDs from resolved detail/home surfaces " +
                "instead of calling TMDB identity bridge helpers."
        )
    }

    @Test
    fun `tmdb scanner ignores harmless service mentions without bridge calls`() {
        val fixture = kotlinFixture(
            """
                package com.nexio.tv.ui.fixture

                import com.nexio.tv.core.tmdb.TmdbService

                class Fixture(
                    private val tmdbService: TmdbService
                ) {
                    // tmdbService can exist here only as text in this fixture; ensure calls are banned.
                    fun name(): String = "TmdbService"
                }
            """.trimIndent()
        )

        val offenders = scanFiles(listOf(fixture), BRIDGE_CALL_PATTERNS)

        if (offenders.isNotEmpty()) {
            fail("Harmless TmdbService/tmdbService mentions must not fail the bridge-call guard: $offenders")
        }
    }

    @Test
    fun `tmdb scanner catches bridge helper calls`() {
        val fixture = kotlinFixture(
            """
                package com.nexio.tv.ui.fixture

                class Fixture {
                    suspend fun one(tmdbService: Any) {
                        tmdbService.ensureTmdbId("tt0944947", "series")
                        ensureTmdbId("tt0944947", "series")
                    }
                }
            """.trimIndent()
        )

        val offenders = scanFiles(listOf(fixture), BRIDGE_CALL_PATTERNS)

        if (offenders.size != 2) {
            fail("Bridge-call guard must catch receiver and direct ensureTmdbId calls: $offenders")
        }
    }

    private fun scanFiles(
        files: List<File>,
        forbiddenPatterns: Map<String, Regex>
    ): List<String> =
        files.flatMap { file ->
            file.readLines().flatMapIndexed { index, line ->
                forbiddenPatterns.mapNotNull { (label, pattern) ->
                    if (pattern.containsMatchIn(line)) {
                        "${file.invariantSeparatorsPath}:${index + 1}:$label"
                    } else {
                        null
                    }
                }
            }
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
        File.createTempFile("NoUiTmdbEnsureIdArchitectureTest", ".kt").apply {
            writeText(source)
            deleteOnExit()
        }

    private companion object {
        private val BRIDGE_CALL_PATTERNS = linkedMapOf(
            "ensureTmdbId(" to Regex("""(?:\b(?:TmdbService|tmdbService)\s*\.\s*)?\bensureTmdbId\s*\(""")
        )
    }
}
