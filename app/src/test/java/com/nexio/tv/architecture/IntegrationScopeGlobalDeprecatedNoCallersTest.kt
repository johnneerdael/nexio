package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F-J-03 architecture pin: production code MUST NOT construct `IntegrationScope.Global`.
 * The value is `@Deprecated` and exists only for back-compat boundary check internals
 * inside `ProfileBoundaryEnforcer`. New code MUST use `GlobalContent`,
 * `GlobalLocalizedContent`, or `GlobalEnglishImage`.
 */
class IntegrationScopeGlobalDeprecatedNoCallersTest {

    @Test
    fun `IntegrationScope_Global is not constructed by any production file outside the allowlist`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv from working dir ${File(".").absolutePath}")

        val allowedRelativePaths = setOf(
            "core/integration/ProfileBoundaryEnforcer.kt",  // back-compat boundary check
            "core/integration/IntegrationScope.kt"           // the value declaration itself
        )

        // Match `IntegrationScope.Global` NOT followed by Content / EnglishImage / LocalizedContent.
        val pattern = Regex("\\bIntegrationScope\\.Global\\b(?!Content|EnglishImage|LocalizedContent)")

        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f ->
                val rel = f.relativeTo(productionRoot).path
                rel !in allowedRelativePaths
            }
            .filter { f -> pattern.containsMatchIn(f.readText()) }
            .map { it.relativeTo(productionRoot).path }
            .toList()

        assertEquals(
            "F-J-03: IntegrationScope.Global must not be constructed in production code (use GlobalContent / " +
                "GlobalLocalizedContent / GlobalEnglishImage). Allowed exceptions: $allowedRelativePaths. " +
                "Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }
}
