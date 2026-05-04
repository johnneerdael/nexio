package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TheIntroDbSettingsAlwaysOnUxTest {

    @Test
    fun `android settings exposes only segment action toggles`() {
        val source = projectRoot()
            .resolve("app/src/main/java/com/nexio/tv/ui/screens/settings/TheIntroDbSettingsScreen.kt")
            .readText()

        assertFalse(
            "TheIntroDB is always on and must not expose a provider-level enabled row.",
            source.contains("theintrodb_enabled") || source.contains("theid_enable_title")
        )
        assertTrue(source.contains("theintrodb_show_intro"))
        assertTrue(source.contains("theintrodb_show_recap"))
        assertTrue(source.contains("theintrodb_show_credits"))
        assertTrue(source.contains("theintrodb_show_preview"))
    }

    @Test
    fun `android persistence cannot restore TheIntroDB as disabled`() {
        val source = projectRoot()
            .resolve("app/src/main/java/com/nexio/tv/data/local/TheIntroDbSettingsDataStore.kt")
            .readText()

        assertFalse(
            "Persisted or synced stale values must not drive TheIntroDB enabled=false.",
            source.contains("enabled = prefs[enabledKey]")
        )
    }

    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(cwd, cwd.parentFile, cwd.resolve("..").canonicalFile)
            .firstOrNull { candidate ->
                candidate.resolve("app/build.gradle.kts").exists() ||
                    candidate.resolve("build.gradle.kts").exists()
            }
            ?: cwd
    }
}
