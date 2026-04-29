package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F-J-04 architecture pin: every `@Deprecated` annotation in production code MUST include
 * a `replaceWith = ReplaceWith(...)` argument so IDE quick-fix can offer a migration.
 *
 * The scan locates `@Deprecated(...)` annotations in production sources and inspects their
 * argument lists for the `replaceWith` keyword (or an inlined `ReplaceWith(...)` expression).
 */
class DeprecatedAnnotationsHaveReplaceWithTest {

    @Test
    fun `every @Deprecated annotation in production has ReplaceWith`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv from working dir ${File(".").absolutePath}")

        // Allowlist: places where ReplaceWith is intentionally absent because the @Deprecated
        // annotation is a forced override of a deprecated interface member
        // (BringIntoViewSpec.scrollAnimationSpec) that has no single-expression replacement.
        val allowedRelativePaths = setOf(
            "ui/components/NexioScrollDefaults.kt",        // forced override of deprecated BringIntoViewSpec.scrollAnimationSpec
            "ui/screens/home/ModernHomeRows.kt",           // forced override of deprecated BringIntoViewSpec.scrollAnimationSpec
            "ui/screens/home/ModernHomeContent.kt"         // forced override of deprecated BringIntoViewSpec.scrollAnimationSpec
        )

        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(productionRoot).path
                rel !in allowedRelativePaths
            }
            .flatMap { f ->
                val text = f.readText()
                annotationsWithoutReplaceWith(text).map { snippet ->
                    f.relativeTo(productionRoot).path to snippet
                }
            }
            .toList()

        assertEquals(
            "F-J-04: every @Deprecated annotation must include ReplaceWith. Offenders: " +
                offenders.joinToString("\n") { "${it.first}: ${it.second.take(100)}" },
            emptyList<Pair<String, String>>(),
            offenders
        )
    }

    /**
     * Find every `@Deprecated(...)` annotation in [text] whose argument list does NOT mention
     * `replaceWith` or `ReplaceWith(`. Handles balanced parentheses inside the argument list.
     */
    private fun annotationsWithoutReplaceWith(text: String): Sequence<String> {
        val results = mutableListOf<String>()
        val keyword = "@Deprecated"
        var index = 0
        while (true) {
            val anchor = text.indexOf(keyword, index)
            if (anchor < 0) break
            // Find the opening '(' after `@Deprecated` (skipping whitespace).
            var i = anchor + keyword.length
            while (i < text.length && text[i].isWhitespace()) i++
            if (i >= text.length || text[i] != '(') {
                index = anchor + keyword.length
                continue
            }
            // Walk to the matching close paren, tracking depth.
            var depth = 0
            val argsStart = i
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            if (depth != 0) {
                // Malformed; skip.
                index = anchor + keyword.length
                continue
            }
            val args = text.substring(argsStart + 1, i)
            if (!args.contains("replaceWith") && !args.contains("ReplaceWith(")) {
                results += text.substring(anchor, (i + 1).coerceAtMost(text.length))
            }
            index = i + 1
        }
        return results.asSequence()
    }
}
