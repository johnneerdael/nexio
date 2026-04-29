package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-02 static guard: no TVDB-specific advanced or timing toggles.
 *
 * This test reads TvdbSettingsScreen.kt and strings.xml to verify that no
 * provider-specific advanced metadata or exact-timing toggles have been added.
 * Per decisions D-13 and D-14, provider routing decides the source, not
 * user-facing toggles.
 *
 * It also verifies that the provider precedence copy remains present so users
 * understand TVDB is used for TV metadata when configured.
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
            "provider-specific timing",
            "Advanced TVDB surfaces",
            "Enable TVDB trailers"
        )

        /**
         * Locate the project root by walking up from the test class output directory.
         * Gradle test execution places class files under app/build/..., so we find
         * the project root by looking for build.gradle.kts.
         */
        private fun findProjectRoot(): File {
            val cwd = File(System.getProperty("user.dir"))
            val candidates = listOfNotNull(
                cwd,
                cwd.parentFile,
                cwd.resolve("..").canonicalFile
            )
            for (candidate in candidates) {
                if (candidate.resolve("app/build.gradle.kts").exists() ||
                    candidate.resolve("build.gradle.kts").exists()
                ) {
                    return candidate
                }
            }
            return cwd
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

    @Test
    fun `provider precedence copy is present in settings screen`() {
        val projectRoot = findProjectRoot()

        // Settings screen must reference provider_precedence_summary
        val settingsScreenFile = projectRoot.resolve(
            "app/src/main/java/com/nexio/tv/ui/screens/settings/TvdbSettingsScreen.kt"
        )
        val settingsScreenContent = if (settingsScreenFile.exists()) {
            settingsScreenFile.readText()
        } else {
            ""
        }

        assertTrue(
            "TvdbSettingsScreen.kt must reference provider_precedence_summary " +
                "so users understand TVDB is used for TV metadata when configured.",
            settingsScreenContent.contains("provider_precedence_summary")
        )

        // strings.xml must contain the actual provider precedence text
        val stringsFile = projectRoot.resolve("app/src/main/res/values/strings.xml")
        val stringsContent = if (stringsFile.exists()) {
            stringsFile.readText()
        } else {
            ""
        }

        assertTrue(
            "strings.xml must contain 'TVDB is used for TV metadata when configured' " +
                "in the provider_precedence_summary string.",
            stringsContent.contains("TVDB is used for TV metadata when configured")
        )
    }
}
