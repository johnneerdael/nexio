package com.nexio.tv.ui.screens.player

import com.nexio.tv.core.tvdb.TvEpisodeMetadata
import com.nexio.tv.core.tmdb.TmdbEpisodeEnrichment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvdbScrobbleCoordinateProjectorTest {

    @Test
    fun `uses unique air date to convert tvdb display coordinate to tmdb native coordinate`() {
        val tvdbEpisodes = mapOf(
            (14 to 23) to TvEpisodeMetadata(
                title = "You Know I've Got You",
                airDate = "2026-04-13"
            )
        )
        val tmdbEpisodes = mapOf(
            (12 to 23) to tmdbEpisode(
                title = "You Know I've Got You",
                airDate = "2026-04-13"
            )
        )

        val projected = TvdbScrobbleCoordinateProjector.projectDisplayToProviderNative(
            displaySeason = 14,
            displayEpisode = 23,
            displayTitle = "You Know I've Got You",
            tvdbEpisodes = tvdbEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )

        assertEquals(12 to 23, projected)
    }

    @Test
    fun `uses matching episode number to disambiguate duplicate tmdb air date matches`() {
        val tvdbEpisodes = mapOf(
            (14 to 23) to TvEpisodeMetadata(
                title = "Provider Title Drift",
                airDate = "2026-04-13"
            )
        )
        val tmdbEpisodes = mapOf(
            (12 to 22) to tmdbEpisode(
                title = "Part One",
                airDate = "2026-04-13"
            ),
            (12 to 23) to tmdbEpisode(
                title = "Part Two",
                airDate = "2026-04-13"
            )
        )

        val projected = TvdbScrobbleCoordinateProjector.projectDisplayToProviderNative(
            displaySeason = 14,
            displayEpisode = 23,
            displayTitle = "Provider Title Drift",
            tvdbEpisodes = tvdbEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )

        assertEquals(12 to 23, projected)
    }

    @Test
    fun `uses title only to disambiguate duplicate tmdb air date matches`() {
        val tvdbEpisodes = mapOf(
            (14 to 23) to TvEpisodeMetadata(
                title = "You Know I've Got You",
                airDate = "2026-04-13"
            )
        )
        val tmdbEpisodes = mapOf(
            (12 to 22) to tmdbEpisode(
                title = "Different Episode",
                airDate = "2026-04-13"
            ),
            (12 to 23) to tmdbEpisode(
                title = "You Know Ive Got You",
                airDate = "2026-04-13"
            )
        )

        val projected = TvdbScrobbleCoordinateProjector.projectDisplayToProviderNative(
            displaySeason = 14,
            displayEpisode = 23,
            displayTitle = "you know ive got you",
            tvdbEpisodes = tvdbEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )

        assertEquals(12 to 23, projected)
    }

    @Test
    fun `returns null when duplicate tmdb air date matches cannot be disambiguated`() {
        val tvdbEpisodes = mapOf(
            (14 to 23) to TvEpisodeMetadata(
                title = "You Know I've Got You",
                airDate = "2026-04-13"
            )
        )
        val tmdbEpisodes = mapOf(
            (12 to 21) to tmdbEpisode(
                title = "Part One",
                airDate = "2026-04-13"
            ),
            (12 to 22) to tmdbEpisode(
                title = "Part Two",
                airDate = "2026-04-13"
            )
        )

        val projected = TvdbScrobbleCoordinateProjector.projectDisplayToProviderNative(
            displaySeason = 14,
            displayEpisode = 23,
            displayTitle = "You Know I've Got You",
            tvdbEpisodes = tvdbEpisodes,
            tmdbEpisodes = tmdbEpisodes
        )

        assertNull(projected)
    }

    private fun tmdbEpisode(
        title: String,
        airDate: String
    ): TmdbEpisodeEnrichment = TmdbEpisodeEnrichment(
        tmdbEpisodeId = null,
        voteAverage = null,
        title = title,
        overview = null,
        thumbnail = null,
        airDate = airDate,
        runtimeMinutes = null
    )
}
