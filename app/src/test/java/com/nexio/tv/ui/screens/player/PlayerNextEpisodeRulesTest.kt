package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.local.NextEpisodeThresholdMode
import com.nexio.tv.data.repository.SkipInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerNextEpisodeRulesTest {

    @Test
    fun `tidb managed content uses credits or preview window instead of threshold`() {
        val skipIntervals = listOf(
            SkipInterval(
                startTime = 1_000.0,
                endTime = Double.MAX_VALUE,
                type = "credits",
                provider = "theintrodb"
            )
        )

        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 980_000,
                durationMs = 1_010_000,
                skipIntervals = skipIntervals,
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 1f
            )
        )

        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_000_000,
                durationMs = 1_010_000,
                skipIntervals = skipIntervals,
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 97f,
                thresholdMinutesBeforeEnd = 1f
            )
        )
    }

    @Test
    fun `non tidb content still uses legacy outro or threshold rules`() {
        val outroIntervals = listOf(
            SkipInterval(
                startTime = 1_000.0,
                endTime = Double.MAX_VALUE,
                type = "outro",
                provider = "animeskip"
            )
        )

        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_000_000,
                durationMs = 1_200_000,
                skipIntervals = outroIntervals,
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f
            )
        )

        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_188_000,
                durationMs = 1_200_000,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f
            )
        )
    }

    @Test
    fun `tidb managed content with no credits or preview window does not fall back to threshold in parallel`() {
        assertFalse(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 1_188_000,
                durationMs = 1_200_000,
                skipIntervals = emptyList(),
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f,
                tidbManagedContent = true
            )
        )
    }

    @Test
    fun `tidb preview windows count as play next windows`() {
        val skipIntervals = listOf(
            SkipInterval(
                startTime = 900.0,
                endTime = 960.0,
                type = "preview",
                provider = "theintrodb"
            )
        )

        assertTrue(
            PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
                positionMs = 905_000,
                durationMs = 1_000_000,
                skipIntervals = skipIntervals,
                thresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
                thresholdPercent = 99f,
                thresholdMinutesBeforeEnd = 2f,
                tidbManagedContent = true
            )
        )
    }
}
