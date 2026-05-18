package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPrecedenceCopyTest {

    @Test
    fun `visible tmdb settings row states tmdb tv default policy`() {
        val stringsXml = File("app/src/main/res/values/strings.xml").readText()
        val settingsScreen = File("app/src/main/java/com/nexio/tv/ui/screens/settings/SettingsScreen.kt")
            .readText()
        val subtitle = Regex("""<string name="settings_tmdb_subtitle">([^<]+)</string>""")
            .find(stringsXml)
            ?.groupValues
            ?.get(1)
            .orEmpty()
        val providerSummary = Regex("""<string name="provider_precedence_summary">([^<]+)</string>""")
            .find(stringsXml)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertTrue(settingsScreen.contains("subtitle = stringResource(R.string.settings_tmdb_subtitle)"))
        assertTrue(subtitle.contains("TMDB is used for movies and standard TV metadata"))
        assertFalse(subtitle.contains("TMDB is used for movie and TV metadata"))
        assertTrue(providerSummary.contains("TMDB is used for movies and standard TV metadata"))
        assertFalse(providerSummary.contains("TMDB is used for movie and TV metadata"))
        assertTrue(subtitle.contains("Kitsu is used for anime"))
        assertTrue(
            subtitle.contains(
                "TheTVDB season numbering can be enabled per show when streams follow TVDB order"
            )
        )
        assertTrue(providerSummary.contains("Kitsu is used for anime"))
        assertTrue(
            providerSummary.contains(
                "TheTVDB season numbering can be enabled per show when streams follow TVDB order"
            )
        )
        assertFalse(subtitle.contains("TVDB is used for TV metadata"))
        assertFalse(providerSummary.contains("TVDB is used for TV metadata"))
        assertFalse(subtitle.contains("fallback metadata"))
        assertFalse(providerSummary.contains("fallback metadata"))
    }
}
