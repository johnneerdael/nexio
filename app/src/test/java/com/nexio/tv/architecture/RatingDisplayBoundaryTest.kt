package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingDisplayBoundaryTest {
    private val defaultLocaleStringFormatPattern = Regex("""String\.format\(\s*"%\.1f""")
    private val suffixFormatPattern = Regex(""""%\.1f[^"]*"\.format\(""")
    private val defaultLocaleOverloadFormatPattern = Regex(
        """String\.format\(\s*Locale\.getDefault\(\)\s*,\s*"%\.1f"""
    )
    private val nonUsLocaleOverloadFormatPattern = Regex(
        """String\.format\(\s*Locale\.(?!US\b)[A-Z_]+\s*,\s*"%\.1f"""
    )

    @Test
    fun `one decimal formatting is rating aware or explicitly locale safe`() {
        val sourceRoot = File("app/src/main/java/com/nexio/tv")
        assertTrue("Expected source root to exist: ${sourceRoot.invariantSeparatorsPath}", sourceRoot.exists())

        val offenders = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("domain/model/TitleRating.kt") }
            .flatMap { file ->
                val text = file.readText()
                unsafeOneDecimalFormattingMatches(text).map { match ->
                    val lineNumber = text.lineNumberAt(match.range.first)
                    val line = text.lineSequence().drop(lineNumber - 1).firstOrNull().orEmpty().trim()
                    "${file.invariantSeparatorsPath}:$lineNumber: $line"
                }
            }
            .toList()

        assertTrue(
            "Use RatingDisplayFormatter for ratings or String.format(Locale.US, ...) for non-rating one-decimal values: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `one decimal formatter boundary samples cover unsafe and safe forms`() {
        assertTrue(hasUnsafeOneDecimalFormatting("""String.format("%.1f", rating)"""))
        assertTrue(hasUnsafeOneDecimalFormatting("""String.format("%.1f GB", gb)"""))
        assertTrue(hasUnsafeOneDecimalFormatting(""""%.1f".format(rating)"""))
        assertTrue(hasUnsafeOneDecimalFormatting(""""%.1f%%".format(value * 100.0)"""))
        assertTrue(hasUnsafeOneDecimalFormatting("""String.format(Locale.getDefault(), "%.1f", rating)"""))
        assertTrue(hasUnsafeOneDecimalFormatting("""String.format(Locale.FRANCE, "%.1f", rating)"""))
        assertTrue(
            hasUnsafeOneDecimalFormatting(
                """
                String.format(
                    "%.1f",
                    rating
                )
                """.trimIndent()
            )
        )
        assertTrue(
            hasUnsafeOneDecimalFormatting(
                """
                String.format(
                    Locale.getDefault(),
                    "%.1f",
                    rating
                )
                """.trimIndent()
            )
        )
        assertTrue(
            hasUnsafeOneDecimalFormatting(
                """
                String.format(
                    Locale.FRANCE,
                    "%.1f",
                    rating
                )
                """.trimIndent()
            )
        )

        assertFalse(hasUnsafeOneDecimalFormatting("""RatingDisplayFormatter.formatTitleRating(rating)"""))
        assertFalse(hasUnsafeOneDecimalFormatting("""RatingDisplayFormatter.formatPercentRating(rating)"""))
        assertFalse(hasUnsafeOneDecimalFormatting("""String.format(Locale.US, "%.1f GB", gb)"""))
        assertFalse(
            hasUnsafeOneDecimalFormatting(
                """
                String.format(
                    Locale.US,
                    "%.1f GB",
                    gb
                )
                """.trimIndent()
            )
        )
    }

    private fun hasUnsafeOneDecimalFormatting(text: String): Boolean {
        return unsafeOneDecimalFormattingMatches(text).any()
    }

    private fun unsafeOneDecimalFormattingMatches(text: String): List<MatchResult> {
        return listOf(
            defaultLocaleStringFormatPattern,
            suffixFormatPattern,
            defaultLocaleOverloadFormatPattern,
            nonUsLocaleOverloadFormatPattern
        )
            .flatMap { pattern -> pattern.findAll(text).toList() }
            .distinctBy { it.range.first }
            .sortedBy { it.range.first }
    }

    private fun String.lineNumberAt(index: Int): Int {
        return take(index).count { it == '\n' } + 1
    }

    @Test
    fun `ui does not add direct tvdb logo fallback behavior`() {
        val uiRoot = File("app/src/main/java/com/nexio/tv/ui")
        assertTrue("Expected UI source root to exist: ${uiRoot.invariantSeparatorsPath}", uiRoot.exists())

        val offenders = uiRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val importsTvdbLogoService = (
                        line.contains("import com.nexio.tv.core.tvdb") ||
                            line.contains("import com.nexio.tv.data.integration.tvdb")
                        ) && line.contains("logo", ignoreCase = true)
                    val localTvdbLogoFallback = line.contains("tvdb", ignoreCase = true) &&
                        line.contains("logo", ignoreCase = true) &&
                        (line.contains("?:") || line.contains("if") || line.contains("="))
                    if (importsTvdbLogoService || localTvdbLogoFallback) {
                        "${file.invariantSeparatorsPath}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "UI must consume hydrated ArtworkBundle.logo and must not implement provider-specific TVDB logo fallback: $offenders",
            offenders.isEmpty()
        )
    }
}
