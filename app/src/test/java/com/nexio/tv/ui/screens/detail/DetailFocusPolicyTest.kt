package com.nexio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailFocusPolicyTest {

    @Test
    fun autoHeroFocusIsSuppressedWhileTrailerIsResolving() {
        assertFalse(
            shouldAutoResetFocusToHero(
                initialHeroFocusRequested = false,
                hasPendingRestoreTarget = false,
                hasPendingRestoreEpisodeId = false,
                isTrailerPlaying = false,
                isTrailerLoading = true
            )
        )
    }

    @Test
    fun autoHeroFocusRunsOnCleanInitialEntry() {
        assertTrue(
            shouldAutoResetFocusToHero(
                initialHeroFocusRequested = false,
                hasPendingRestoreTarget = false,
                hasPendingRestoreEpisodeId = false,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun trailerActionTrapsRightNavigationAtTheEndOfTheHeroRow() {
        val actions = buildHeroActionOrder(
            trailerAvailable = true
        )

        val trailerDirections = resolveHeroActionDirections(
            action = HeroAction.TRAILER,
            orderedActions = actions
        )

        assertEquals(
            listOf(
                HeroAction.PLAY,
                HeroAction.LIBRARY,
                HeroAction.TRAILER
            ),
            actions
        )
        assertEquals(HeroAction.LIBRARY, trailerDirections.left)
        assertEquals(null, trailerDirections.right)
        assertTrue(trailerDirections.trapRight)
    }

    @Test
    fun finalHeroActionTrapsRightWhenTrailerIsUnavailable() {
        val actions = buildHeroActionOrder(
            trailerAvailable = false
        )

        val libraryDirections = resolveHeroActionDirections(
            action = HeroAction.LIBRARY,
            orderedActions = actions
        )

        assertEquals(
            listOf(
                HeroAction.PLAY,
                HeroAction.LIBRARY
            ),
            actions
        )
        assertEquals(HeroAction.PLAY, libraryDirections.left)
        assertEquals(null, libraryDirections.right)
        assertTrue(libraryDirections.trapRight)
    }

    @Test
    fun seriesHeroDownPrefersSeasonNavigationBeforePeopleTabs() {
        assertEquals(
            HeroDownTarget.SEASONS,
            resolveHeroDownTarget(
                isSeries = true,
                hasSeasonEntry = true,
                hasPeopleSection = true
            )
        )
    }

    @Test
    fun movieHeroDownFallsBackToPeopleTabsWhenAvailable() {
        assertEquals(
            HeroDownTarget.PEOPLE,
            resolveHeroDownTarget(
                isSeries = false,
                hasSeasonEntry = false,
                hasPeopleSection = true
            )
        )
    }

    @Test
    fun sectionRestorerStaysDisabledUntilSectionWasVisited() {
        assertFalse(
            shouldEnableSectionFocusRestorer(
                hasVisitedSection = false,
                hasActiveRestoreTarget = false
            )
        )
    }

    @Test
    fun sectionRestorerEnablesForExplicitRestoreOrVisitedSection() {
        assertTrue(
            shouldEnableSectionFocusRestorer(
                hasVisitedSection = true,
                hasActiveRestoreTarget = false
            )
        )
        assertTrue(
            shouldEnableSectionFocusRestorer(
                hasVisitedSection = false,
                hasActiveRestoreTarget = true
            )
        )
    }

    @Test
    fun lowerContentFocusStaysDisabledOnCleanInitialEntry() {
        assertFalse(
            shouldEnableLowerContentFocus(
                hasUserMovedDownFromHero = false,
                hasVisitedLowerContent = false,
                hasActiveRestoreTarget = false
            )
        )
    }

    @Test
    fun lowerContentFocusEnablesAfterExplicitHeroDownOrRestore() {
        assertTrue(
            shouldEnableLowerContentFocus(
                hasUserMovedDownFromHero = true,
                hasVisitedLowerContent = false,
                hasActiveRestoreTarget = false
            )
        )
        assertTrue(
            shouldEnableLowerContentFocus(
                hasUserMovedDownFromHero = false,
                hasVisitedLowerContent = false,
                hasActiveRestoreTarget = true
            )
        )
    }

    @Test
    fun episodeFocusCountsAsVisitedLowerContent() {
        assertTrue(
            hasVisitedDetailLowerContent(
                hasVisitedSeasonTabs = false,
                hasVisitedEpisodes = true,
                hasVisitedPeopleTabs = false
            )
        )
    }

    @Test
    fun moviePeopleSectionReturnsUpToHeroPlay() {
        assertEquals(
            SectionUpTarget.HERO_PLAY,
            resolvePeopleSectionUpTarget(
                isSeries = false,
                hasEpisodeEntry = false
            )
        )
    }

    @Test
    fun seriesPeopleSectionReturnsUpToEpisodeEntryWhenAvailable() {
        assertEquals(
            SectionUpTarget.EPISODE_ENTRY,
            resolvePeopleSectionUpTarget(
                isSeries = true,
                hasEpisodeEntry = true
            )
        )
    }

    @Test
    fun reclaimStaysDisabledWhenPlayNeverLanded() {
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = false,
                playIsFocused = false,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun reclaimRunsWhenPlayLostFocusWithoutUserNavigation() {
        assertTrue(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = false,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun reclaimDoesNotRunWhilePlayStillHoldsFocus() {
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = true,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun reclaimBacksOffOnceUserIntentionallyNavigatesAway() {
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = false,
                userHasNavigatedFromPlay = true,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun reclaimYieldsToExplicitPendingRestoreTargets() {
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = false,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = true,
                isTrailerPlaying = false,
                isTrailerLoading = false
            )
        )
    }

    @Test
    fun reclaimYieldsDuringTrailerStartupAndPlayback() {
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = false,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = false,
                isTrailerLoading = true
            )
        )
        assertFalse(
            shouldReclaimPlayFocus(
                playEverFocused = true,
                playIsFocused = false,
                userHasNavigatedFromPlay = false,
                hasPendingRestoreTarget = false,
                isTrailerPlaying = true,
                isTrailerLoading = false
            )
        )
    }
}
