package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileBoundaryArchitectureTest {
    @Test
    fun `metadata providers use global content scopes not profile scopes`() {
        val providerFiles = listOf(
            "app/src/main/java/com/nexio/tv/data/integration/tmdb/TmdbIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/tvdb/TvdbIntegrationProvider.kt",
            "app/src/main/java/com/nexio/tv/data/integration/kitsu/KitsuIntegrationProvider.kt"
        )

        val offenders = providerFiles.flatMap { path ->
            val text = File(path).readText()
            Regex("""IntegrationScope\.(Profile|ProfileLocal|Account)\(""")
                .findAll(text)
                .map { "$path uses profile-bound scope for global metadata: ${it.value}" }
                .toList()
        }

        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `image cache keys always include english image language`() {
        val source = File("app/src/main/java/com/nexio/tv/core/image/ArtworkImageCacheKeys.kt").readText()

        assertTrue(
            "ArtworkImageCacheKeys must encode imageLang:en.",
            source.contains("imageLang:en")
        )
        assertTrue(
            "ArtworkImageCacheKeys must not accept display language as an argument.",
            !Regex("""fun\s+\w+\([^)]*language""").containsMatchIn(source)
        )
    }
}
