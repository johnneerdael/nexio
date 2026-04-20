package com.nexio.tv.data.mapper

import com.nexio.tv.data.remote.dto.MetaDto
import com.nexio.tv.data.remote.dto.VideoDto
import org.junit.Assert.assertEquals
import org.junit.Test

class MetaMapperTest {
    @Test
    fun `series video uses imdb episode coordinate when addon provides one`() {
        val meta = MetaDto(
            id = "kitsu:44081",
            type = "series",
            name = "Kimetsu no Yaiba: Yuukaku-hen",
            videos = listOf(
                VideoDto(
                    id = "kitsu:44081:1",
                    title = "Sound Hashira Tengen Uzui",
                    season = 1,
                    episode = 1,
                    imdbId = "tt9335498",
                    imdbSeason = 3,
                    imdbEpisode = 1
                )
            )
        ).toDomain()

        val episode = meta.videos.single()
        assertEquals("tt9335498:3:1", episode.id)
        assertEquals(1, episode.season)
        assertEquals(1, episode.episode)
    }

    @Test
    fun `movie video keeps original addon id`() {
        val meta = MetaDto(
            id = "kitsu:42586",
            type = "movie",
            name = "Kimetsu no Yaiba: Mugen Ressha-hen",
            videos = listOf(
                VideoDto(
                    id = "kitsu:42586",
                    title = "Episode 1",
                    season = 1,
                    episode = 1,
                    imdbId = "tt11032374",
                    imdbSeason = 1,
                    imdbEpisode = 1
                )
            )
        ).toDomain()

        assertEquals("kitsu:42586", meta.videos.single().id)
    }
}
