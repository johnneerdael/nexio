package com.nexio.tv.ui.screens.detail

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaDetailsKitsuEpisodeSupportTest {
    @Test
    fun `anime detail type is treated as series-like without changing raw addon type`() {
        assertEquals(ContentType.SERIES, parseDetailApiTypeToContentType("anime"))
    }

    @Test
    fun `anime detail with episode videos is treated as series-like for CTA state`() {
        val meta = meta(
            rawType = "anime",
            type = ContentType.UNKNOWN,
            videos = listOf(video(season = 1, episode = 1))
        )

        assertTrue(isSeriesDetailMeta(meta))
    }

    @Test
    fun `movie detail with behavior hint video is not treated as series-like`() {
        val meta = meta(
            rawType = "movie",
            type = ContentType.MOVIE,
            videos = listOf(video(season = 1, episode = 1))
        )

        assertFalse(isSeriesDetailMeta(meta))
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

    private fun meta(
        rawType: String,
        type: ContentType,
        videos: List<Video>
    ): Meta {
        return Meta(
            id = "kitsu:12",
            type = type,
            rawType = rawType,
            name = "One Piece",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = videos,
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )
    }

    private fun video(season: Int, episode: Int): Video {
        return Video(
            id = "tt0388629:$season:$episode",
            title = "Episode",
            released = null,
            thumbnail = null,
            streams = emptyList(),
            season = season,
            episode = episode,
            overview = null
        )
    }
}
