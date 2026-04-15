package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Wave 0 validation scaffold for UX-02: no TVDB-specific advanced or timing toggles.
 *
 * This is a static guard test that reads TvdbSettingsScreen.kt and strings.xml
 * to verify that no provider-specific advanced metadata or exact-timing toggles
 * have been added. Per decisions D-13 and D-14, provider routing decides the source,
 * not user-facing toggles.
 *
 * This test must remain green throughout Phase 9 execution.
 */
class TvdbSettingsNoAdvancedToggleTest {

    companion object {
        /**
         * Phrases that must NOT appear in the TVDB settings screen or string resources.
         * These indicate a provider-specific toggle that violates UX-02.
         */
        private val FORBIDDEN_PHRASES = listOf(
            "Use TVDB advanced metadata",
            "TVDB advanced toggle",
            "Use TVDB timing",
            "Exact air-time toggle",
            "provider-specific timing"
        )

        /**
         * Locate the project root by walking up from the test class output directory.
         * Gradle test execution places class files under app/build/..., so we find
         * the project root by looking for build.gradle.kts.
         */
        private fun findProjectRoot(): File {
            // Try common locations relative to CWD
            val candidates = listOf(
                File(System.getProperty("user.dir")),
                File(System.getProperty("user.dir")).parentFile,
                File(System.getProperty("user.dir")).resolve("..").canonicalFile
            )
            for (candidate in candidates) {
                if (candidate.resolve("app/build.gradle.kts").exists() ||
                    candidate.resolve("build.gradle.kts").exists()
                ) {
                    return candidate
                }
            }
            // Fall back to CWD
            return File(System.getProperty("user.dir"))
        }
    }

    @Test
    fun `does not add tvdb advanced or exact timing toggle`() {
        val projectRoot = findProjectRoot()

        // Read TvdbSettingsScreen.kt
        val settingsScreenFile = projectRoot.resolve(
            "app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt"
        )
        val settingsScreenContent = if (settingsScreenFile.exists()) {
            settingsScreenFile.readText()
        } else {
            // If file doesn't exist, the guard still holds (no toggles possible)
            ""
        }

        // Read strings.xml
        val stringsFile = projectRoot.resolve("app/src/main/res/values/strings.xml")
        val stringsContent = if (stringsFile.exists()) {
            stringsFile.readText()
        } else {
            ""
        }

        val combinedContent = settingsScreenContent + stringsContent

        // Assert none of the forbidden phrases appear (case-insensitive).
        for (phrase in FORBIDDEN_PHRASES) {
            assertFalse(
                "TvdbSettingsScreen.kt or strings.xml must not contain '$phrase' " +
                    "(violates UX-02: no provider-specific advanced/timing toggles). " +
                    "Provider routing decides the source per D-13/D-14.",
                combinedContent.contains(phrase, ignoreCase = true)
            )
        }
    }
}
