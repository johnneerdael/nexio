package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderPrecedenceCopyTest {

    @Test
    fun `provider precedence summary states tvdb tmdb and poster ratings order`() {
        val stringsXml = File("app/src/main/res/values/strings.xml").readText()
        val summary = Regex("""<string name="provider_precedence_summary">([^<]+)</string>""")
            .find(stringsXml)
            ?.groupValues
            ?.get(1)
            .orEmpty()

        assertTrue(summary.contains("TVDB is used for TV metadata"))
        assertTrue(summary.contains("TMDB is used for movies and fallback metadata"))
        assertTrue(summary.contains("Poster-ratings providers override supported poster artwork"))
    }
}
