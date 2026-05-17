package com.nexio.tv.data.repository

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class ContinueWatchingEpisodeCoordinateProjectorTest {

    @Test
    fun `matches provider coordinate to tvdb coordinate by episode title`() {
        val episodes = mapOf(
            (13 to 1) to episode(season = 13, episode = 1, title = "Not The One", airDate = "2025-01-01"),
            (14 to 1) to episode(season = 14, episode = 1, title = "The Multiverse", airDate = "2026-02-03")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 13,
            requestedEpisode = 1,
            requestedTitle = "The Multiverse",
            episodes = episodes
        )

        assertEquals(14, projected?.season)
        assertEquals(1, projected?.episode)
        assertEquals("The Multiverse", projected?.episodeTitle)
        assertEquals("2026-02-03", projected?.firstAired)
    }

    @Test
    fun `uses tvdb map coordinate even when metadata carries provider numbering`() {
        val episodes = mapOf(
            (14 to 1) to episode(season = 13, episode = 1, title = "The Multiverse", airDate = "2026-02-03")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 13,
            requestedEpisode = 1,
            requestedTitle = "The Multiverse",
            episodes = episodes
        )

        assertEquals(14, projected?.season)
        assertEquals(1, projected?.episode)
        assertEquals("The Multiverse", projected?.episodeTitle)
        assertEquals("2026-02-03", projected?.firstAired)
    }

    @Test
    fun `keeps exact coordinate when title is blank and tvdb coordinate exists`() {
        val episodes = mapOf(
            (2 to 7) to episode(season = 2, episode = 7, title = "The Seventh", airDate = "2026-03-04")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 2,
            requestedEpisode = 7,
            requestedTitle = null,
            episodes = episodes
        )

        assertEquals(2, projected?.season)
        assertEquals(7, projected?.episode)
        assertEquals("The Seventh", projected?.episodeTitle)
        assertEquals("2026-03-04", projected?.firstAired)
    }

    @Test
    fun `matches provider coordinate to tvdb coordinate by first air date when title is absent`() {
        val episodes = mapOf(
            (12 to 20) to episode(season = 12, episode = 20, title = "Wrong Era", airDate = "2024-05-02"),
            (14 to 20) to episode(season = 14, episode = 20, title = "Reward Challenge", airDate = "2026-05-12")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 12,
            requestedEpisode = 20,
            requestedTitle = null,
            requestedFirstAired = "2026-05-12T10:00:00.000Z",
            episodes = episodes
        )

        assertEquals(14, projected?.season)
        assertEquals(20, projected?.episode)
        assertEquals("Reward Challenge", projected?.episodeTitle)
        assertEquals("2026-05-12", projected?.firstAired)
    }

    @Test
    fun `activity date prefers current tvdb same episode over stale exact title match`() {
        val episodes = mapOf(
            (12 to 20) to episode(season = 12, episode = 20, title = "The Reaper Is Coming", airDate = "2025-03-31"),
            (14 to 19) to episode(season = 14, episode = 19, title = "Sold the Dream", airDate = "2026-04-05"),
            (14 to 20) to episode(season = 14, episode = 20, title = "Maggots", airDate = "2026-04-06"),
            (14 to 21) to episode(season = 14, episode = 21, title = "Half and Half", airDate = "2026-04-07")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 12,
            requestedEpisode = 20,
            requestedTitle = "The Reaper Is Coming",
            requestedFirstAired = "2025-03-31T10:00:00.000Z",
            requestedActivityAtMs = Instant.parse("2026-04-18T21:32:00Z").toEpochMilli(),
            episodes = episodes
        )

        assertEquals(14, projected?.season)
        assertEquals(20, projected?.episode)
        assertEquals("Maggots", projected?.episodeTitle)
        assertEquals("2026-04-06", projected?.firstAired)
    }

    @Test
    fun `does not force anime through tvdb projection`() {
        val episodes = mapOf(
            (14 to 1) to episode(season = 14, episode = 1, title = "The Multiverse", airDate = "2026-02-03")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "anime",
            requestedSeason = 13,
            requestedEpisode = 1,
            requestedTitle = "The Multiverse",
            episodes = episodes
        )

        assertNull(projected)
    }

    @Test
    fun `duplicate title matches choose lowest season and episode independent of insertion order`() {
        val lowCoordinate = (1 to 5) to episode(season = 1, episode = 5, title = "Shared Title", airDate = "2025-01-05")
        val highCoordinate = (2 to 3) to episode(season = 2, episode = 3, title = "Shared Title", airDate = "2025-02-03")

        val lowFirst = mapOf(lowCoordinate, highCoordinate)
        val highFirst = mapOf(highCoordinate, lowCoordinate)

        val lowFirstProjected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 9,
            requestedEpisode = 9,
            requestedTitle = "Shared Title",
            episodes = lowFirst
        )

        val highFirstProjected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 9,
            requestedEpisode = 9,
            requestedTitle = "Shared Title",
            episodes = highFirst
        )

        assertEquals(1, lowFirstProjected?.season)
        assertEquals(5, lowFirstProjected?.episode)
        assertEquals(1, highFirstProjected?.season)
        assertEquals(5, highFirstProjected?.episode)
    }

    @Test
    fun `requested coordinate wins when its title matches duplicate title`() {
        val episodes = mapOf(
            (1 to 1) to episode(season = 1, episode = 1, title = "Shared Title", airDate = "2025-01-01"),
            (3 to 4) to episode(season = 3, episode = 4, title = "Shared Title", airDate = "2025-03-04")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 3,
            requestedEpisode = 4,
            requestedTitle = "Shared Title",
            episodes = episodes
        )

        assertEquals(3, projected?.season)
        assertEquals(4, projected?.episode)
        assertEquals("2025-03-04", projected?.firstAired)
    }

    @Test
    fun `normalizes punctuation and case when matching episode title`() {
        val episodes = mapOf(
            (2 to 8) to episode(season = 2, episode = 8, title = "The: Multiverse", airDate = "2026-04-05")
        )

        val projected = ContinueWatchingEpisodeCoordinateProjector.projectFromEpisodeMap(
            contentType = "series",
            requestedSeason = 9,
            requestedEpisode = 9,
            requestedTitle = "the multiverse!",
            episodes = episodes
        )

        assertEquals(2, projected?.season)
        assertEquals(8, projected?.episode)
        assertEquals("The: Multiverse", projected?.episodeTitle)
    }

    private fun episode(
        season: Int,
        episode: Int,
        title: String,
        airDate: String
    ): TvEpisodeMetadata {
        return TvEpisodeMetadata(
            seasonNumber = season,
            episodeNumber = episode,
            title = title,
            airDate = airDate
        )
    }
}
