package com.nexio.tv.ui.screens.detail

internal enum class HeroAction {
    PLAY,
    LIBRARY,
    TRAILER
}

internal data class HeroActionDirections(
    val left: HeroAction?,
    val right: HeroAction?,
    val trapLeft: Boolean,
    val trapRight: Boolean
)

internal enum class HeroDownTarget {
    SEASONS,
    PEOPLE,
    NONE
}

internal enum class SectionUpTarget {
    HERO_PLAY,
    EPISODE_ENTRY
}

internal fun shouldAutoResetFocusToHero(
    initialHeroFocusRequested: Boolean,
    hasPendingRestoreTarget: Boolean,
    hasPendingRestoreEpisodeId: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerLoading: Boolean
): Boolean {
    return !initialHeroFocusRequested &&
        !hasPendingRestoreTarget &&
        !hasPendingRestoreEpisodeId &&
        !isTrailerPlaying &&
        !isTrailerLoading
}

internal fun buildHeroActionOrder(
    trailerAvailable: Boolean
): List<HeroAction> = buildList {
    add(HeroAction.PLAY)
    add(HeroAction.LIBRARY)
    if (trailerAvailable) {
        add(HeroAction.TRAILER)
    }
}

internal fun resolveHeroActionDirections(
    action: HeroAction,
    orderedActions: List<HeroAction>
): HeroActionDirections {
    val index = orderedActions.indexOf(action)
    require(index >= 0) { "Action $action is not present in $orderedActions" }

    val left = orderedActions.getOrNull(index - 1)
    val right = orderedActions.getOrNull(index + 1)
    return HeroActionDirections(
        left = left,
        right = right,
        trapLeft = left == null,
        trapRight = right == null
    )
}

internal fun resolveHeroDownTarget(
    isSeries: Boolean,
    hasSeasonEntry: Boolean,
    hasPeopleSection: Boolean
): HeroDownTarget {
    return when {
        isSeries && hasSeasonEntry -> HeroDownTarget.SEASONS
        hasPeopleSection -> HeroDownTarget.PEOPLE
        else -> HeroDownTarget.NONE
    }
}

internal fun resolveHeroDownScrollIndex(
    downTarget: HeroDownTarget,
    hasSeasonSection: Boolean
): Int? {
    return when (downTarget) {
        HeroDownTarget.SEASONS -> if (hasSeasonSection) 1 else null
        HeroDownTarget.PEOPLE -> if (hasSeasonSection) 3 else 1
        HeroDownTarget.NONE -> null
    }
}

internal fun shouldEnableSectionFocusRestorer(
    hasVisitedSection: Boolean,
    hasActiveRestoreTarget: Boolean
): Boolean = hasVisitedSection || hasActiveRestoreTarget

internal fun shouldEnableLowerContentFocus(
    hasUserMovedDownFromHero: Boolean,
    hasVisitedLowerContent: Boolean,
    hasActiveRestoreTarget: Boolean
): Boolean = hasUserMovedDownFromHero || hasVisitedLowerContent || hasActiveRestoreTarget

internal fun hasVisitedDetailLowerContent(
    hasVisitedSeasonTabs: Boolean,
    hasVisitedEpisodes: Boolean,
    hasVisitedPeopleTabs: Boolean
): Boolean = hasVisitedSeasonTabs || hasVisitedEpisodes || hasVisitedPeopleTabs

internal fun resolvePeopleSectionUpTarget(
    isSeries: Boolean,
    hasEpisodeEntry: Boolean
): SectionUpTarget {
    return if (isSeries && hasEpisodeEntry) {
        SectionUpTarget.EPISODE_ENTRY
    } else {
        SectionUpTarget.HERO_PLAY
    }
}

/**
 * Returns true when the detail screen should re-steal focus back onto the hero Play button
 * after it previously landed there and then lost focus without an intentional user navigation.
 *
 * The guard is intentionally narrow so it never fights against the user:
 * - It only applies while no explicit restore is pending.
 * - It never runs while a trailer is starting or playing (trailer flow owns focus).
 * - It requires that Play had been focused at least once (we are re-stealing, not claiming).
 * - It exits immediately once the user navigates away (down/left/right/other hero action).
 */
internal fun shouldReclaimPlayFocus(
    playEverFocused: Boolean,
    playIsFocused: Boolean,
    userHasNavigatedFromPlay: Boolean,
    hasPendingRestoreTarget: Boolean,
    isTrailerPlaying: Boolean,
    isTrailerLoading: Boolean
): Boolean {
    return playEverFocused &&
        !playIsFocused &&
        !userHasNavigatedFromPlay &&
        !hasPendingRestoreTarget &&
        !isTrailerPlaying &&
        !isTrailerLoading
}
