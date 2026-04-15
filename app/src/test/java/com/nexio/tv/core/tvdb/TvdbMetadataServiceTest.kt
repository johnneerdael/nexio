package com.nexio.tv.core.tvdb

import com.nexio.tv.data.remote.api.TvdbAirsDays
import com.nexio.tv.data.remote.api.TvdbAlias
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbArtworkRecord
import com.nexio.tv.data.remote.api.TvdbCompanyRecord
import com.nexio.tv.data.remote.api.TvdbContentRating
import com.nexio.tv.data.remote.api.TvdbEpisodeRecord
import com.nexio.tv.data.remote.api.TvdbGenreRecord
import com.nexio.tv.data.remote.api.TvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSeriesEpisodesResponse
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import com.nexio.tv.data.remote.api.TvdbStatusRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import retrofit2.http.GET

class TvdbMetadataServiceTest {

    @Test
    fun `tvdb api exposes extended series and season episodes endpoints`() {
        val extended = TvdbApi::class.java.getMethod(
            "getSeriesExtended",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            java.lang.Boolean::class.java
        )
        val episodes = TvdbApi::class.java.getMethod(
            "getSeriesEpisodes",
            String::class.java,
            Int::class.javaPrimitiveType,
            String::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaObjectType,
            Int::class.javaObjectType,
            String::class.java
        )

        assertEquals("series/{id}/extended", extended.getAnnotation(GET::class.java)?.value)
        assertEquals("series/{id}/episodes/{seasonType}", episodes.getAnnotation(GET::class.java)?.value)
    }

    @Test
    fun `series extended dto carries metadata fields used by TVDB mapper`() {
        val record = TvdbSeriesExtendedRecord(
            id = 121361,
            name = "Game of Thrones",
            image = "https://art.example/fallback-poster.jpg",
            airsDays = TvdbAirsDays(sunday = true),
            airsTime = "21:00",
            aliases = listOf(TvdbAlias(name = "GoT")),
            artworks = listOf(TvdbArtworkRecord(image = "https://art.example/poster.jpg", type = 2, score = 91.5)),
            averageRuntime = 57,
            contentRatings = listOf(TvdbContentRating(name = "TV-MA", country = "usa")),
            country = "usa",
            episodes = listOf(TvdbEpisodeRecord(id = 1001, seasonNumber = 1, number = 1, name = "Winter Is Coming")),
            firstAired = "2011-04-17",
            genres = listOf(TvdbGenreRecord(name = "Drama")),
            originalCountry = "usa",
            originalLanguage = "eng",
            originalNetwork = TvdbCompanyRecord(name = "HBO"),
            overview = "Nine noble families fight for control.",
            latestNetwork = TvdbCompanyRecord(name = "HBO"),
            remoteIds = listOf(TvdbRemoteId(id = "tt0944947", sourceName = "imdb")),
            score = 8.4,
            status = TvdbStatusRecord(name = "Ended")
        )

        assertEquals("21:00", record.airsTime)
        assertEquals("Drama", record.genres.single().name)
        assertEquals("tt0944947", record.remoteIds.single().id)
        assertEquals("Winter Is Coming", record.episodes.single().name)
    }

    @Test
    fun `episode dto carries placement and linked movie fields`() {
        val response = TvdbSeriesEpisodesResponse(
            status = "success",
            data = listOf(
                TvdbEpisodeRecord(
                    absoluteNumber = 1,
                    aired = "2011-04-17",
                    airsAfterSeason = 0,
                    airsBeforeEpisode = 1,
                    airsBeforeSeason = 2,
                    finaleType = "series",
                    id = 1001,
                    image = "https://art.example/episode.jpg",
                    linkedMovie = 4444,
                    name = "Winter Is Coming",
                    number = 1,
                    overview = "The first episode.",
                    runtime = 62,
                    seasonNumber = 1
                )
            )
        )

        val episode = response.data.single()
        assertNotNull(episode.id)
        assertEquals(1, episode.absoluteNumber)
        assertEquals(4444, episode.linkedMovie)
        assertEquals("series", episode.finaleType)
    }
}
