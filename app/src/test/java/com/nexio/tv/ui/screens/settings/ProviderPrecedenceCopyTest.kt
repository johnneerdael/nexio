package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPrecedenceCopyTest {

    @Test
    fun `provider precedence summary states tmdb tv default policy`() {
        val stringsXml = File("app/src/main/res/values/strings.xml").readText()
        val summary = Regex("""<string name="provider_precedence_summary">([^<]+)</string>""")
            .find(stringsXml)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertTrue(summary.contains("TMDB is used for movie and TV metadata"))
        assertTrue(summary.contains("Kitsu is used for anime"))
        assertTrue(
            summary.contains(
                "TheTVDB season numbering can be enabled per show when streams follow TVDB order"
            )
        )
        assertFalse(summary.contains("TVDB is used for TV metadata"))
        assertFalse(summary.contains("fallback metadata"))
    }
}
