package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.SkipInterval
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerSegmentCtaPolicyTest {

    @Test
    fun `replace skip button with play next only for tv aired tidb credits preview and visible card`() {
        val credits = SkipInterval(
            startTime = 1200.0,
            endTime = Double.MAX_VALUE,
            type = "credits",
            provider = "theintrodb"
        )

        assertTrue(
            PlayerSegmentCtaPolicy.shouldReplaceSkipButtonWithPlayNext(
                activeInterval = credits,
                hasEpisodeContext = true,
                hasAiredNextEpisode = true,
                showNextEpisodeCard = true
            )
        )

        assertFalse(
            PlayerSegmentCtaPolicy.shouldReplaceSkipButtonWithPlayNext(
                activeInterval = credits,
                hasEpisodeContext = false,
                hasAiredNextEpisode = true,
                showNextEpisodeCard = true
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.shouldReplaceSkipButtonWithPlayNext(
                activeInterval = credits,
                hasEpisodeContext = true,
                hasAiredNextEpisode = false,
                showNextEpisodeCard = true
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.shouldReplaceSkipButtonWithPlayNext(
                activeInterval = credits,
                hasEpisodeContext = true,
                hasAiredNextEpisode = true,
                showNextEpisodeCard = false
            )
        )
    }

    @Test
    fun `tidb play next overlay only shows for aired tv in window`() {
        assertTrue(
            PlayerSegmentCtaPolicy.shouldShowTidbPlayNextOverlay(
                hasEpisodeContext = true,
                hasAiredNextEpisode = true,
                inTidbPlayNextWindow = true
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.shouldShowTidbPlayNextOverlay(
                hasEpisodeContext = false,
                hasAiredNextEpisode = true,
                inTidbPlayNextWindow = true
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.shouldShowTidbPlayNextOverlay(
                hasEpisodeContext = true,
                hasAiredNextEpisode = false,
                inTidbPlayNextWindow = true
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.shouldShowTidbPlayNextOverlay(
                hasEpisodeContext = true,
                hasAiredNextEpisode = true,
                inTidbPlayNextWindow = false
            )
        )
    }

    @Test
    fun `only tidb credits and preview count as play next candidate intervals`() {
        assertTrue(
            PlayerSegmentCtaPolicy.isTidbCreditsOrPreview(
                SkipInterval(10.0, 20.0, "credits", "theintrodb")
            )
        )
        assertTrue(
            PlayerSegmentCtaPolicy.isTidbCreditsOrPreview(
                SkipInterval(10.0, 20.0, "preview", "theintrodb")
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.isTidbCreditsOrPreview(
                SkipInterval(10.0, 20.0, "intro", "theintrodb")
            )
        )
        assertFalse(
            PlayerSegmentCtaPolicy.isTidbCreditsOrPreview(
                SkipInterval(10.0, 20.0, "credits", "animeskip")
            )
        )
    }
}
