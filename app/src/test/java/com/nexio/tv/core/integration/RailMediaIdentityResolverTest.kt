package com.nexio.tv.core.integration

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibraryEntry
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RailMediaIdentityResolverTest {
    private val resolver = RailMediaIdentityResolver()

    @Test
    fun `library entries prefer imdb as canonical key and preserve secondary ids`() {
        val entry = LibraryEntry(
            id = "movie:trakt:42",
            type = "movie",
            name = "Fight Club",
            poster = null,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "1999",
            imdbRating = null,
            genres = emptyList(),
            addonBaseUrl = null,
            imdbId = "tt0137523",
            tmdbId = 550,
            traktId = 42
        )

        val resolved = resolver.fromLibraryEntry(entry)

        assertEquals("movie:imdb:tt0137523", resolved.mediaIdentity.mediaKey)
        assertEquals(3, resolved.externalIds.size)
        assertTrue(resolved.externalIds.any { it.provider == "IMDB" && it.externalId == "tt0137523" })
        assertTrue(resolved.externalIds.any { it.provider == "TMDB" && it.externalId == "550" })
        assertTrue(resolved.externalIds.any { it.provider == "TRAKT" && it.externalId == "42" })
    }

    @Test
    fun `meta preview falls back to raw id when no canonical external ids are available`() {
        val preview = MetaPreview(
            id = "custom-addon-id",
            type = ContentType.MOVIE,
            name = "Unknown",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = "2024",
            imdbRating = null,
            genres = emptyList()
        )

        val resolved = resolver.fromPreview(preview)

        assertEquals("movie:raw:custom-addon-id", resolved.mediaIdentity.mediaKey)
        assertTrue(resolved.externalIds.isEmpty())
    }

    @Test
    fun `continue watching resolves imdb ids before raw content ids`() {
        val progress = WatchProgress(
            contentId = "series:tt0944947",
            contentType = "series",
            name = "Game of Thrones",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "series:tt0944947:1:1",
            season = 1,
            episode = 1,
            episodeTitle = "Winter Is Coming",
            position = 10L,
            duration = 100L,
            lastWatched = 1_000L
        )

        val resolved = resolver.fromWatchProgress(progress, title = "Game of Thrones", year = 2011)

        assertEquals("series:imdb:tt0944947", resolved.mediaIdentity.mediaKey)
        assertTrue(resolved.externalIds.any { it.provider == "IMDB" && it.externalId == "tt0944947" })
    }
}
