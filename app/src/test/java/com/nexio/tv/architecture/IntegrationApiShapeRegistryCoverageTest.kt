package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F-C-02 pin: every apiShapeId argument in production must be a property reference
 * (e.g. `TraktApiShapes.WATCHED`) — not a string literal. String literals defeat the
 * `*ApiShapes` policy registry. The pin catches both:
 *  - named-arg form: `apiShapeId = "trakt.foo"`
 *  - positional-arg form: `callAuthenticated("trakt.foo", ...)` and `callPublic("trakt.foo", ...)`
 */
class IntegrationApiShapeRegistryCoverageTest {

    @Test
    fun `apiShapeId arguments are property references in all production files`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv from working dir ${File(".").absolutePath}")

        // Pattern A: named-arg form
        val namedPattern = Regex("\\bapiShapeId\\s*=\\s*\"")

        // Pattern B: positional-arg form — `callAuthenticated("...` or `callPublic("...`
        // Specifically look for the literal as the FIRST argument to those methods. Not all
        // callAuthenticated/callPublic calls pass apiShapeId positionally — but those that do
        // pass a string literal are exactly the F-C-02 violations.
        val positionalPattern = Regex("\\b(callAuthenticated|callPublic)\\s*\\(\\s*\"")

        val offenders = productionRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                val text = f.readText()
                val rel = f.relativeTo(productionRoot).path
                val findings = mutableListOf<Pair<String, String>>()
                if (namedPattern.containsMatchIn(text)) findings += rel to "named-arg apiShapeId literal"
                if (positionalPattern.containsMatchIn(text)) findings += rel to "positional callAuthenticated/callPublic literal"
                findings
            }
            .toList()

        assertEquals(
            "F-C-02: apiShapeId must be a property reference (e.g. TraktApiShapes.WATCHED), " +
                "not a literal. Offenders: $offenders",
            emptyList<Pair<String, String>>(),
            offenders
        )
    }
}
