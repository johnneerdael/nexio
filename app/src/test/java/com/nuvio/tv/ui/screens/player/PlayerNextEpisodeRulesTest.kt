package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {
    @Test
    fun `resolves next episode across season boundary`() {
        val videos = listOf(
            video(season = 1, episode = 9),
            video(season = 1, episode = 10),
            video(season = 2, episode = 1)
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 10
        )

        assertEquals(2, next?.season)
        assertEquals(1, next?.episode)
    }

    @Test
    fun `returns null for last episode`() {
        val videos = listOf(
            video(season = 1, episode = 1),
            video(season = 1, episode = 2)
        )

        val next = PlayerNextEpisodeRules.resolveNextEpisode(
            videos = videos,
            currentSeason = 1,
            currentEpisode = 2
        )

        assertNull(next)
    }

    @Test
    fun `shows card at outro start when outro interval exists`() {
        val intervals = listOf(
            SkipInterval(
                startTime = 1100.0,
                endTime = 1200.0,
                type = "outro",
                provider = "introdb"
            )
        )

        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_099_000L,
                durationMs = 1_500_000L,
                skipIntervals = intervals
            )
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_100_000L,
                durationMs = 1_500_000L,
                skipIntervals = intervals
            )
        )
    }

    @Test
    fun `shows card at ninety five percent when no outro exists`() {
        val duration = 1_000_000L

        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 949_000L,
                durationMs = duration,
                skipIntervals = emptyList()
            )
        )
        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 950_000L,
                durationMs = duration,
                skipIntervals = emptyList()
            )
        )
    }

    private fun video(season: Int, episode: Int): Video {
        return Video(
            id = "v-$season-$episode",
            title = "Episode $episode",
            released = null,
            thumbnail = null,
            season = season,
            episode = episode,
            overview = null,
            runtime = null
        )
    }
}
