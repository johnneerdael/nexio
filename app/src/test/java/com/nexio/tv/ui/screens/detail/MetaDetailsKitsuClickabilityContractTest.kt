package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MetaDetailsKitsuClickabilityContractTest {

    @Test
    fun `cast section gates focusability on navigable cast ids`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val file = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/CastSection.kt")
        assertTrue(file.exists())
        val source = file.readText()

        assertTrue(source.contains("canFocus = enableFocus && isClickable"))
        assertTrue(source.contains("member.tmdbId != null || member.tvdbPeopleId != null"))
    }

    @Test
    fun `company logos section gates focusability on tmdb company ids`() {
        val projectRoot = System.getProperty("user.dir")
            ?: error("Cannot determine project root")
        val file = File(projectRoot, "app/src/main/java/com/nexio/tv/ui/screens/detail/CompanyLogosSection.kt")
        assertTrue(file.exists())
        val source = file.readText()

        assertTrue(source.contains("clickable = company.tmdbId != null"))
        assertTrue(source.contains("canFocus = clickable"))
    }
}
