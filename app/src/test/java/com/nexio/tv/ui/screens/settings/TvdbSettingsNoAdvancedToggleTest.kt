package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UX-02 static guard: no TVDB-specific advanced or timing toggles.
 *
 * This test reads SettingsScreen.kt and strings.xml to verify that no
 * provider-specific advanced metadata or exact-timing toggles have been added.
 * Per decisions D-13 and D-14, provider routing decides the source, not
 * user-facing toggles.
 *
 * It also verifies that the provider precedence copy remains present so users
 * understand TMDB is the default movies and standard TV metadata authority, while TVDB is
 * limited to show-level season numbering when streams follow TVDB order.
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
            "Enable TVDB trailers",
            "TVDB metadata provider toggle",
            "Use TVDB as metadata provider",
            "TVDB is used for TV metadata",
            "TVDB metadata is included by default",
            "TVDB is active by default for TV metadata",
            "TVDB is ready for TV metadata"
        )

        /**
         * Locate the project root by walking up from the test class output directory.
         * Gradle test execution places class files under app/build/..., so we find
         * the project root by looking for build.gradle.kts.
         */
        private fun findProjectRoot(): File {
            // user.dir is a mandatory JVM system property — always non-null at runtime.
            val cwd = File(System.getProperty("user.dir") ?: ".")
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

        val settingsScreenContent = projectRoot.resolve(
            "app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt"
        ).readText()

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
                "SettingsScreen.kt or strings.xml must not contain '$phrase' " +
                    "(violates UX-02: no provider-specific advanced/timing toggles). " +
                    "Provider routing decides the source per D-13/D-14.",
                combinedContent.contains(phrase, ignoreCase = true)
            )
        }
    }

    @Test
    fun `visible tmdb settings copy states tmdb tv default policy`() {
        val projectRoot = findProjectRoot()

        val stringsFile = projectRoot.resolve("app/src/main/res/values/strings.xml")
        val stringsContent = if (stringsFile.exists()) {
            stringsFile.readText()
        } else {
            ""
        }

        assertTrue(
            "settings_tmdb_subtitle must contain the TMDB movies and standard TV metadata policy.",
            visibleTmdbSubtitle(stringsContent).contains("TMDB is used for movies and standard TV metadata")
        )
        assertFalse(
            "settings_tmdb_subtitle must not use broader movie and TV metadata wording.",
            visibleTmdbSubtitle(stringsContent).contains("TMDB is used for movie and TV metadata")
        )
        assertTrue(
            "settings_tmdb_subtitle must explain show-level TheTVDB season numbering.",
            visibleTmdbSubtitle(stringsContent).contains(
                "TheTVDB season numbering can be enabled per show when streams follow TVDB order"
            )
        )
        assertFalse(
            "settings_tmdb_subtitle must not describe TVDB as the global TV metadata authority.",
            visibleTmdbSubtitle(stringsContent).contains("TVDB is used for TV metadata")
        )
    }

    private fun visibleTmdbSubtitle(stringsContent: String): String =
        Regex("""<string name="settings_tmdb_subtitle">([^<]+)</string>""")
            .find(stringsContent)
            ?.groupValues
            ?.get(1)
            .orEmpty()
}
