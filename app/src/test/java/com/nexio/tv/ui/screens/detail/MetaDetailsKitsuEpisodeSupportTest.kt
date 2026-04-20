package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaDetailsKitsuEpisodeSupportTest {
    @Test
    fun `anime detail type is treated as series-like without changing raw addon type`() {
        assertEquals(ContentType.SERIES, parseDetailApiTypeToContentType("anime"))
    }

    @Test
    fun `buildKitsuEpisodeVideos creates stremio episode ids from metadata`() {
        val videos = buildKitsuEpisodeVideos(
            seriesId = "kitsu:1",
            episodeLabel = "Episode",
            episodeMap = mapOf(
                (1 to 2) to TvEpisodeMetadata(
                    seasonNumber = 1,
                    episodeNumber = 2,
                    title = "Stray Dog Strut",
                    airDate = "1998-04-10",
                    runtimeMinutes = 24
                ),
                (1 to 1) to TvEpisodeMetadata(
                    seasonNumber = 1,
                    episodeNumber = 1,
                    title = "Asteroid Blues",
                    airDate = "1998-04-03",
                    runtimeMinutes = 24
                )
            )
        )

        assertEquals(listOf("kitsu:1:1:1", "kitsu:1:1:2"), videos.map { it.id })
        assertEquals(listOf("Asteroid Blues", "Stray Dog Strut"), videos.map { it.title })
        assertEquals(listOf(1, 1), videos.map { it.season })
        assertEquals(listOf(1, 2), videos.map { it.episode })
    }
}
