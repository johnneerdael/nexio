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
            forbiddenPatterns = linkedMapOf(
                "TmdbService" to Regex("""\bTmdbService\b"""),
                "tmdbService" to Regex("""\btmdbService\b"""),
                "ensureTmdbId(" to Regex("""\bensureTmdbId\s*\("""),
                ".ensureTmdbId(" to Regex("""\.ensureTmdbId\s*\(""")
            )
        )

        failIfNotEmpty(
            offenders,
            "UI and MainActivity must consume stable IDs from resolved detail/home surfaces " +
                "instead of calling TMDB identity bridge helpers."
        )
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
}
