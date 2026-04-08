package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.repository.SkipInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTidbTest {

    @Test
    fun `tidb managed path triggers play next during credits window`() {
        val intervals = listOf(
            SkipInterval(
                startTime = 1200.0,
                endTime = Double.MAX_VALUE,
                type = "credits",
                provider = "theintrodb"
            )
        )

        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = 1_220_000,
            durationMs = 1_500_000,
            skipIntervals = intervals,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 99f,
            thresholdMinutesBeforeEnd = 2f
        )

        assertTrue(shouldShow)
    }

    @Test
    fun `tidb managed path does not fall back to generic near-end threshold outside credits preview window`() {
        val intervals = listOf(
            SkipInterval(
                startTime = 1200.0,
                endTime = Double.MAX_VALUE,
                type = "credits",
                provider = "theintrodb"
            )
        )

        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = 1_100_000,
            durationMs = 1_101_000,
            skipIntervals = intervals,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 97f,
            thresholdMinutesBeforeEnd = 1f
        )

        assertFalse(shouldShow)
    }

    @Test
    fun `non tidb path still honors legacy outro threshold`() {
        val intervals = listOf(
            SkipInterval(
                startTime = 1180.0,
                endTime = Double.MAX_VALUE,
                type = "outro",
                provider = "animeskip"
            )
        )

        val shouldShow = PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = 1_181_000,
            durationMs = 1_500_000,
            skipIntervals = intervals,
            thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            thresholdPercent = 99f,
            thresholdMinutesBeforeEnd = 2f
        )

        assertTrue(shouldShow)
    }
}
