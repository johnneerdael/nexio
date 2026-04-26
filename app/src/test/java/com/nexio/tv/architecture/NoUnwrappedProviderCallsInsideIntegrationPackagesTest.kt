package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoUnwrappedProviderCallsInsideIntegrationPackagesTest {
    @Test
    fun `integration package raw provider calls must be near a runtime spec`() {
        val offenders = File("app/src/main/java/com/nexio/tv/data/integration")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                val source = lines.joinToString("\n")
                val wrapperNames = runtimeWrapperFunctionNames(source)
                val lineStarts = mutableListOf(0)
                lines.dropLast(1).fold(0) { offset, line ->
                    val next = offset + line.length + 1
                    lineStarts += next
                    next
                }
                lines.mapIndexedNotNull { index, line ->
                    val rawCall = Regex("""\b[a-z][A-Za-z0-9_]*Api\.[A-Za-z0-9_]+\(""").containsMatchIn(line) ||
                        Regex("""\b[a-z][A-Za-z0-9_]*Api\.get[A-Za-z0-9_]*\(""").containsMatchIn(line) ||
                        Regex("""\b[a-z][A-Za-z0-9_]*Api\.request[A-Za-z0-9_]*\(""").containsMatchIn(line)
                    if (!rawCall) return@mapIndexedNotNull null

                    val rawCallOffset = lineStarts[index] + (
                        Regex("""\b[a-z][A-Za-z0-9_]*Api\.[A-Za-z0-9_]+\(""").find(line)?.range?.first
                            ?: line.indexOf("Api.")
                        )
                    val wrapped = rawProviderCallIsRuntimeOwned(source, rawCallOffset, wrapperNames)

                    if (wrapped) null else "${file.path}:${index + 1}:${line.trim()}"
                }
            }
            .sorted()
            .toList()

        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `raw provider scanner rejects calls outside runtime loader lambdas`() {
        val source = """
            class Fixture(private val runtime: IntegrationRuntime, private val tmdbApi: TmdbApi) {
                suspend fun bypass() {
                    tmdbApi.getMovieDetails(1)
                    runtime.get(IntegrationSpec(load = { tmdbApi.getMovieDetails(2) }))
                }
            }
        """.trimIndent()
        val wrappers = runtimeWrapperFunctionNames(source)
        val bypassOffset = source.indexOf("tmdbApi.getMovieDetails(1)")
        val wrappedOffset = source.indexOf("tmdbApi.getMovieDetails(2)")

        assertFalse(rawProviderCallIsRuntimeOwned(source, bypassOffset, wrappers))
        assertTrue(rawProviderCallIsRuntimeOwned(source, wrappedOffset, wrappers))
    }

    private fun enclosingFunctionBlock(source: String, index: Int): String? {
        val prefix = source.substring(0, index)
        val functionStart = Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
            .findAll(prefix)
            .lastOrNull()
            ?.range
            ?.first
            ?: return null
        val bodyStart = source.indexOf('{', functionStart).takeIf { it >= 0 } ?: return null
        val expressionBodyStart = source.indexOf('=', functionStart).takeIf { it >= 0 && it < bodyStart }
        if (expressionBodyStart != null) {
            val nextFunctionStart = Regex("""\n\s*(?:private\s+|public\s+|internal\s+|protected\s+)?(?:suspend\s+)?fun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
                .find(source, expressionBodyStart + 1)
                ?.range
                ?.first
                ?: source.length
            return source.substring(functionStart, nextFunctionStart)
        }
        var depth = 0
        var inString = false
        var escaped = false
        for (position in bodyStart until source.length) {
            val char = source[position]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return source.substring(functionStart, position + 1)
                    }
                }
            }
        }
        return source.substring(functionStart)
    }

    private val integrationRuntimeMarkers = listOf(
        "IntegrationSpec(",
        "IntegrationCallSpec(",
        "IntegrationStreamSpec(",
        "runtime.get(",
        "runtime.call(",
        "runtime.open("
    )

    private fun hasIntegrationRuntimeMarker(text: String): Boolean =
        integrationRuntimeMarkers.any(text::contains)

    private fun functionBlocks(source: String): List<Pair<String, String>> =
        Regex("""\bfun\s+(?:<[^>]+>\s*)?([A-Za-z0-9_]+)\s*\(""")
            .findAll(source)
            .mapNotNull { match ->
                val block = enclosingFunctionBlock(source, match.range.last + 1) ?: return@mapNotNull null
                match.groupValues[1] to block
            }
            .toList()

    private fun runtimeWrapperFunctionNames(source: String): Set<String> {
        val blocks = functionBlocks(source)
        val wrappers = blocks
            .filter { (_, block) -> hasIntegrationRuntimeMarker(block) }
            .mapTo(mutableSetOf()) { (name, _) -> name }

        var changed = true
        while (changed) {
            changed = false
            blocks.forEach { (name, block) ->
                if (name !in wrappers && wrappers.any { wrapper -> block.containsFunctionCall(wrapper) }) {
                    wrappers += name
                    changed = true
                }
                if (name in wrappers) {
                    blocks.forEach { (candidate, _) ->
                        if (candidate !in wrappers && block.containsFunctionCall(candidate)) {
                            wrappers += candidate
                            changed = true
                        }
                    }
                }
            }
        }
        return wrappers
    }

    private fun String.containsFunctionCall(name: String): Boolean =
        Regex("""\b${Regex.escape(name)}\s*\(""").containsMatchIn(this)

    private fun isRuntimeWrappedProviderLoader(block: String, wrapperNames: Set<String>): Boolean =
        hasIntegrationRuntimeMarker(block) ||
            Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]*WithinRuntimeLoad\s*\(""").containsMatchIn(block) ||
            wrapperNames.any { wrapper -> block.containsFunctionCall(wrapper) }

    private fun rawProviderCallIsRuntimeOwned(source: String, callOffset: Int, wrapperNames: Set<String>): Boolean {
        val functionBlock = enclosingFunctionBlock(source, callOffset).orEmpty()
        if (Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]*WithinRuntimeLoad\s*\(""").containsMatchIn(functionBlock)) {
            return true
        }
        val functionName = Regex("""\bfun\s+(?:<[^>]+>\s*)?([A-Za-z0-9_]+)\s*\(""")
            .find(functionBlock)
            ?.groupValues
            ?.get(1)
        if (functionName in wrapperNames && !hasIntegrationRuntimeMarker(functionBlock)) {
            return true
        }
        return isInsideRuntimeOwnedLambda(source, callOffset, wrapperNames)
    }

    private fun isInsideRuntimeOwnedLambda(source: String, callOffset: Int, wrapperNames: Set<String>): Boolean {
        val lambdaPattern = Regex("""\b(load|call|request|execute|open)\s*=\s*\{""")
        val trailingSpecLambdaPattern = Regex("""Integration(?:Call|Stream)?Spec\([\s\S]{0,1200}\)\s*\{""")
        val prefix = source.substring(0, callOffset)
        if (trailingSpecLambdaPattern.findAll(prefix)
                .toList()
                .asReversed()
                .any { match ->
                    val braceStart = source.indexOf('{', match.range.last - 1)
                    val braceEnd = matchingBraceEnd(source, braceStart) ?: return@any false
                    callOffset in braceStart..braceEnd
                }
        ) {
            return true
        }
        return lambdaPattern.findAll(prefix)
            .toList()
            .asReversed()
            .any { match ->
                val braceStart = source.indexOf('{', match.range.first)
                val braceEnd = matchingBraceEnd(source, braceStart) ?: return@any false
                if (callOffset !in braceStart..braceEnd) return@any false

                val ownerStart = Regex("""\bfun\s+(?:<[^>]+>\s*)?[A-Za-z0-9_]+\s*\(""")
                    .findAll(source.substring(0, match.range.first))
                    .lastOrNull()
                    ?.range
                    ?.first
                    ?: (match.range.first - 1200).coerceAtLeast(0)
                val ownerPrefix = source.substring(ownerStart, match.range.first)
                hasIntegrationRuntimeMarker(ownerPrefix) ||
                    wrapperNames.any { wrapper -> Regex("""\b${Regex.escape(wrapper)}\s*\(""").containsMatchIn(ownerPrefix) }
            }
    }

    private fun matchingBraceEnd(source: String, braceStart: Int): Int? {
        if (braceStart < 0 || source.getOrNull(braceStart) != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (position in braceStart until source.length) {
            val char = source[position]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (char == '\\') {
                    escaped = true
                } else if (char == '"') {
                    inString = false
                }
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return position
                }
            }
        }
        return null
    }
}
