package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableIdBundleArchitectureTest {
    @Test
    fun `home renderer does not import stable id bundle resolver internals`() {
        val files = listOf(
            File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeRows.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/home/ModernHomeContent.kt"),
            File("app/src/main/java/com/nexio/tv/ui/screens/home/ContentCard.kt")
        ).filter { it.exists() }

        files.forEach { file ->
            val text = file.readText()
            assertFalse(file.path, text.contains("StableIdBundleResolver"))
            assertFalse(file.path, text.contains("StableIdBundleRequest"))
        }
    }

    @Test
    fun `stable id resolver source does not chase simkl or trakt tracking ids`() {
        val text = File("app/src/main/java/com/nexio/tv/core/metadata/router/StableIdBundleResolver.kt").readText()

        assertFalse(text.contains("imdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("tmdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("tvdbToTrakt", ignoreCase = true))
        assertFalse(text.contains("imdbToSimkl", ignoreCase = true))
        assertFalse(text.contains("tmdbToSimkl", ignoreCase = true))
        assertFalse(text.contains("tvdbToSimkl", ignoreCase = true))
        assertTrue(text.contains("MetadataPrimaryProvider.TRAKT"))
        assertTrue(text.contains("MetadataPrimaryProvider.SIMKL"))
    }
}
