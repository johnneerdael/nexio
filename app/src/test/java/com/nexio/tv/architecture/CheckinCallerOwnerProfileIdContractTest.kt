package com.nexio.tv.architecture

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * F2-J-02 pin: every production call to TrackingScrobbleService.checkin(...) MUST supply
 * a non-null ownerProfileId. Implicit/ambient profile fallback is forbidden.
 */
class CheckinCallerOwnerProfileIdContractTest {

    @Test
    fun `every production checkin call supplies ownerProfileId`() {
        val productionRoot = generateSequence(File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv") }
            .firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate app/src/main/java/com/nexio/tv from working dir ${File(".").absolutePath}")

        // Scoped to trackingScrobbleService.checkin(...) to avoid false positives from
        // HTTP-client calls (TraktIntegrationProvider, SimklScrobbleMutationAdapter, etc.)
        // which naturally omit ownerProfileId because they operate below the profile layer.
        val checkinPattern = Regex("trackingScrobbleService\\.checkin\\s*\\(")
        val offenders = mutableListOf<String>()

        productionRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            val text = f.readText()
            checkinPattern.findAll(text).forEach { match ->
                val open = text.indexOf('(', match.range.last)
                if (open < 0) return@forEach
                val close = findMatchingParen(text, open)
                if (close < 0) return@forEach
                val args = text.substring(open + 1, close)
                if (!args.contains("ownerProfileId")) {
                    val snippet = text.substring(match.range.first, (close + 1).coerceAtMost(text.length)).take(120)
                    offenders += "${f.relativeTo(productionRoot)}: $snippet"
                }
            }
        }

        assertEquals(
            "F2-J-02: every checkin(...) call must supply ownerProfileId. Offenders: $offenders",
            emptyList<String>(),
            offenders
        )
    }

    private fun findMatchingParen(text: String, openIdx: Int): Int {
        var depth = 1
        var i = openIdx + 1
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> { depth--; if (depth == 0) return i }
            }
            i++
        }
        return -1
    }
}
