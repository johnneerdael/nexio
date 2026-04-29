package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * F-G-03 pin: recordCount in continue_watching.snapshot_write events MUST include
 * traktUpNextItems.size, not just resumeItems.size + nextUpItems.size.
 *
 * Without this, trace bundles undercount CW records by the number of Trakt-driven up-next
 * tiles, making operator-side analysis miss Trakt-rail content.
 */
class ContinueWatchingRecordCountIncludesTraktUpNextTest {

    @Test
    fun `recordCount formula includes traktUpNextItems at all 3 emission sites`() {
        // Read source file - search in parent directories
        val sourceText = generateSequence(java.io.File(".")) { it.parentFile }
            .map { it.resolve("app/src/main/java/com/nexio/tv/data/repository/ContinueWatchingSnapshotService.kt") }
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("Could not find ContinueWatchingSnapshotService.kt source file")

        // Find all recordCount = ... resumeItems ... nextUpItems patterns (line-based)
        val recordCountLines = sourceText.split("\n")
            .filter { it.contains("recordCount") && it.contains("resumeItems") && it.contains("nextUpItems") }

        // All lines should also include traktUpNextItems
        val linesWithTrakt = recordCountLines.filter { it.contains("traktUpNextItems") }

        assertEquals(
            "F-G-03: all recordCount computation sites must include traktUpNextItems. " +
                    "Found ${recordCountLines.size} recordCount lines, but only ${linesWithTrakt.size} include traktUpNextItems.\n" +
                    "Missing traktUpNextItems in: ${(recordCountLines - linesWithTrakt.toSet()).joinToString("\n")}",
            recordCountLines.size,
            linesWithTrakt.size
        )
    }
}
