package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F-B-02 pin: production code MUST NOT directly construct `FieldResolver()` or
 * `ProviderPlanRunner(emptySet())`. Hilt is the sole construction path. Direct
 * fallback constructions silently swallow Hilt failures into non-functional facades.
 */
class FieldResolverInjectionContractTest {

    @Test
    fun `no production file constructs FieldResolver_no_arg or ProviderPlanRunner_emptySet`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv from working dir ${File(".").absolutePath}")

        val allowedRelativePaths = setOf<String>()  // empty — no exemptions

        val patterns = listOf(
            "FieldResolver()" to Regex("\\bFieldResolver\\(\\s*\\)"),
            "ProviderPlanRunner(emptySet())" to Regex("\\bProviderPlanRunner\\(\\s*emptySet\\(\\s*\\)\\s*\\)")
        )

        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(productionRoot).path
                rel !in allowedRelativePaths
            }
            .flatMap { f ->
                val text = f.readText()
                patterns.mapNotNull { (label, regex) ->
                    if (regex.containsMatchIn(text)) f.relativeTo(productionRoot).path to label else null
                }
            }
            .toList()

        assertEquals(
            "F-B-02: production code must not construct FieldResolver() / ProviderPlanRunner(emptySet()). " +
                "Use Hilt injection. Offenders: $offenders",
            emptyList<Pair<String, String>>(),
            offenders
        )
    }
}
