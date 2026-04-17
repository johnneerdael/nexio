package com.nexio.tv.ui.screens.stream

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamRuntimeResolverTest {

    @Test
    fun `episode runtime is used when available`() {
        val meta = meta(
            runtime = "49",
            videos = listOf(video(season = 50, episode = 6, runtime = 45))
        )

        assertEquals(
            45,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 6
            )
        )
    }

    @Test
    fun `series average runtime is used when episode runtime is missing`() {
        val meta = meta(
            runtime = "49",
            videos = listOf(video(season = 50, episode = 8, runtime = null))
        )

        assertEquals(
            49,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    @Test
    fun `series average runtime is used when episode metadata is absent`() {
        val meta = meta(
            runtime = "49 minutes",
            videos = emptyList()
        )

        assertEquals(
            49,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    @Test
    fun `movie runtime parsing still uses title runtime`() {
        val meta = meta(
            type = ContentType.MOVIE,
            runtime = "152 min",
            videos = emptyList()
        )

        assertEquals(
            152,
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = null,
                episode = null
            )
        )
    }

    @Test
    fun `runtime stays null when episode and series runtime are missing`() {
        val meta = meta(
            runtime = null,
            videos = listOf(video(season = 50, episode = 8, runtime = null))
        )

        assertNull(
            resolveStreamRuntimeMinutes(
                meta = meta,
                season = 50,
                episode = 8
            )
        )
    }

    private fun video(
        season: Int,
        episode: Int,
        runtime: Int?
    ): Video {
        return Video(
            id = "tt0239195:$season:$episode",
            title = "Episode",
            released = null,
            thumbnail = null,
            streams = emptyList(),
            season = season,
            episode = episode,
            overview = null,
            runtime = runtime
        )
    }

    private fun meta(
        type: ContentType = ContentType.SERIES,
        runtime: String?,
        videos: List<Video>
    ): Meta {
        return Meta(
            id = "tt0239195",
            type = type,
            name = "Survivor",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = runtime,
            director = emptyList(),
            cast = emptyList(),
            videos = videos,
            country = null,
            awards = null,
            language = "en",
            links = emptyList()
        )
    }
}
