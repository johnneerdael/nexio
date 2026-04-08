package com.nexio.tv.ui.screens.player

import com.nexio.tv.data.repository.SkipInterval

internal object PlayerSegmentCtaPolicy {
    fun isTidbCreditsOrPreview(interval: SkipInterval?): Boolean {
        if (interval == null) return false
        return interval.provider == "theintrodb" &&
            (interval.type == "credits" || interval.type == "preview")
    }

    fun shouldShowTidbPlayNextOverlay(
        hasEpisodeContext: Boolean,
        hasAiredNextEpisode: Boolean,
        inTidbPlayNextWindow: Boolean
    ): Boolean {
        return hasEpisodeContext && hasAiredNextEpisode && inTidbPlayNextWindow
    }

    fun shouldReplaceSkipButtonWithPlayNext(
        activeInterval: SkipInterval?,
        hasEpisodeContext: Boolean,
        hasAiredNextEpisode: Boolean,
        showNextEpisodeCard: Boolean
    ): Boolean {
        return isTidbCreditsOrPreview(activeInterval) &&
            hasEpisodeContext &&
            hasAiredNextEpisode &&
            showNextEpisodeCard
    }
}
