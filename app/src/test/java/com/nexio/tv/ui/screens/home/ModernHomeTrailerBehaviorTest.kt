package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeTrailerBehaviorTest {

    @Test
    fun `home trailer autoplay remains locked while screensaver is visible`() {
        assertFalse(
            shouldUnlockModernHomeTrailerAutoplay(
                autoplayEnabled = true,
                screensaverVisible = true,
                startupSplashVisible = false,
                externalTrailerTakeoverActive = false,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `home trailer autoplay remains locked while startup splash is visible`() {
        assertFalse(
            shouldUnlockModernHomeTrailerAutoplay(
                autoplayEnabled = true,
                screensaverVisible = false,
                startupSplashVisible = true,
                externalTrailerTakeoverActive = false,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `home trailer autoplay unlocks after delay when enabled and resumed`() {
        assertTrue(
            shouldUnlockModernHomeTrailerAutoplay(
                autoplayEnabled = true,
                screensaverVisible = false,
                startupSplashVisible = false,
                externalTrailerTakeoverActive = false,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `home trailer autoplay remains locked while external manual trailer takeover is active`() {
        assertFalse(
            shouldUnlockModernHomeTrailerAutoplay(
                autoplayEnabled = true,
                screensaverVisible = false,
                startupSplashVisible = false,
                externalTrailerTakeoverActive = true,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `focused trailer preview requires resumed lifecycle after delay`() {
        assertFalse(
            shouldActivateFocusedTrailerPreviewAfterDelay(
                shouldActivateFocusedPosterFlow = true,
                isVerticalRowsScrolling = false,
                selectionStillFocused = true,
                lifecycleResumed = false
            )
        )
    }

    @Test
    fun `focused trailer preview activates when selection still matches and lifecycle is resumed`() {
        assertTrue(
            shouldActivateFocusedTrailerPreviewAfterDelay(
                shouldActivateFocusedPosterFlow = true,
                isVerticalRowsScrolling = false,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `back press dismisses modern home trailer when hero or expanded trailer is active`() {
        assertTrue(
            shouldDismissModernHomeTrailerOnBack(
                heroTrailerActive = true,
                expandedCardTrailerActive = false,
                fullscreenTrailerActive = false
            )
        )
        assertTrue(
            shouldDismissModernHomeTrailerOnBack(
                heroTrailerActive = false,
                expandedCardTrailerActive = true,
                fullscreenTrailerActive = false
            )
        )
        assertFalse(
            shouldDismissModernHomeTrailerOnBack(
                heroTrailerActive = false,
                expandedCardTrailerActive = false,
                fullscreenTrailerActive = false
            )
        )
    }

    @Test
    fun `back press dismisses modern home trailer when fullscreen trailer is active`() {
        assertTrue(
            shouldDismissModernHomeTrailerOnBack(
                heroTrailerActive = false,
                expandedCardTrailerActive = false,
                fullscreenTrailerActive = true
            )
        )
    }

    @Test
    fun `focused poster trailer counts as active time only when unmuted`() {
        assertTrue(
            shouldTreatFocusedPosterTrailerPlaybackAsActiveTime(
                hasActivePlayback = true,
                trailerMuted = false
            )
        )
        assertFalse(
            shouldTreatFocusedPosterTrailerPlaybackAsActiveTime(
                hasActivePlayback = true,
                trailerMuted = true
            )
        )
        assertFalse(
            shouldTreatFocusedPosterTrailerPlaybackAsActiveTime(
                hasActivePlayback = false,
                trailerMuted = false
            )
        )
    }

    @Test
    fun `modern home trailer counts as active time only when internal playback is unmuted`() {
        assertTrue(
            shouldTreatModernHomeTrailerPlaybackAsActiveTime(
                internalHomeTrailerPlaying = true,
                trailerMuted = false
            )
        )
        assertFalse(
            shouldTreatModernHomeTrailerPlaybackAsActiveTime(
                internalHomeTrailerPlaying = true,
                trailerMuted = true
            )
        )
        assertFalse(
            shouldTreatModernHomeTrailerPlaybackAsActiveTime(
                internalHomeTrailerPlaying = false,
                trailerMuted = false
            )
        )
    }

    @Test
    fun `expanded card trailer target falls back to hero media when expanded playback is unavailable`() {
        assertEquals(
            FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
            resolveEffectiveModernHomeTrailerPlaybackTarget(
                requestedTarget = FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                effectiveExpandEnabled = false
            )
        )
    }

    @Test
    fun `expanded card trailer target remains expanded when expanded playback is available`() {
        assertEquals(
            FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
            resolveEffectiveModernHomeTrailerPlaybackTarget(
                requestedTarget = FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD,
                effectiveExpandEnabled = true
            )
        )
    }

    @Test
    fun `focused trailer preview requests after autoplay unlock when metadata says trailer exists`() {
        assertTrue(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = true,
                hasResolvedPreview = false,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
                externalTrailerTakeoverActive = false,
                alreadyRetriedAfterUnlock = false
            )
        )
    }

    @Test
    fun `focused trailer preview requests after autoplay unlock even before metadata availability is known`() {
        assertTrue(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = false,
                hasResolvedPreview = false,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
                externalTrailerTakeoverActive = false,
                alreadyRetriedAfterUnlock = false
            )
        )
    }

    @Test
    fun `focused trailer preview does not request after autoplay unlock when preview already exists`() {
        assertFalse(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = true,
                hasResolvedPreview = true,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
                externalTrailerTakeoverActive = false,
                alreadyRetriedAfterUnlock = false
            )
        )
    }

    @Test
    fun `focused trailer preview does not request after autoplay unlock once retry was already consumed`() {
        assertFalse(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = true,
                hasResolvedPreview = false,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
                externalTrailerTakeoverActive = false,
                alreadyRetriedAfterUnlock = true
            )
        )
    }

    @Test
    fun `focused trailer preview does not request while external manual trailer takeover is active`() {
        assertFalse(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = true,
                hasResolvedPreview = false,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
                externalTrailerTakeoverActive = true,
                alreadyRetriedAfterUnlock = false
            )
        )
    }

    @Test
    fun `up press promotes active hero trailer to fullscreen`() {
        assertTrue(
            shouldPromoteModernHomeHeroTrailerToFullscreen(
                key = Key.DirectionUp,
                eventType = KeyEventType.KeyDown,
                heroTrailerPlaying = true,
                fullscreenTrailerActive = false
            )
        )
    }

    @Test
    fun `up press does not promote when hero trailer is not playing`() {
        assertFalse(
            shouldPromoteModernHomeHeroTrailerToFullscreen(
                key = Key.DirectionUp,
                eventType = KeyEventType.KeyDown,
                heroTrailerPlaying = false,
                fullscreenTrailerActive = false
            )
        )
    }

    @Test
    fun `non up keys do not promote hero trailer to fullscreen`() {
        assertFalse(
            shouldPromoteModernHomeHeroTrailerToFullscreen(
                key = Key.DirectionDown,
                eventType = KeyEventType.KeyDown,
                heroTrailerPlaying = true,
                fullscreenTrailerActive = false
            )
        )
    }

    @Test
    fun `fullscreen overlay text is only shown while timer is active`() {
        assertFalse(
            shouldShowModernHomeFullscreenTextOverlay(
                fullscreenTrailerActive = true,
                overlayTimedOut = false
            )
        )
        assertFalse(
            shouldShowModernHomeFullscreenTextOverlay(
                fullscreenTrailerActive = true,
                overlayTimedOut = true
            )
        )
        assertFalse(
            shouldShowModernHomeFullscreenTextOverlay(
                fullscreenTrailerActive = false,
                overlayTimedOut = false
            )
        )
    }

    @Test
    fun `modern home hero trailers are muted until fullscreen is active`() {
        assertTrue(
            resolveModernHomeHeroTrailerMuted(
                fullscreenTrailerActive = false
            )
        )
        assertFalse(
            resolveModernHomeHeroTrailerMuted(
                fullscreenTrailerActive = true
            )
        )
    }

    @Test
    fun `trailer end clears fullscreen hero session and restores focused row`() {
        val (endedState, focusRestore) = handleModernHomeTrailerEnded(
            focusedTrailerFocusKey = "row-1:item-2",
            activeItemIndex = 2,
            focusedTrailerRowKey = "row-1",
            state = ModernHomeTrailerEndedState(
                unlockedTrailerFocusKey = "row-1:item-2",
                pendingHeroTrailerFocusKey = "row-1:item-2",
                heroTrailerFullscreenMode = true,
                fullscreenTrailerTextTimedOut = true,
                heroFullscreenHintTimedOut = true
            )
        )

        assertNull(endedState.unlockedTrailerFocusKey)
        assertNull(endedState.pendingHeroTrailerFocusKey)
        assertFalse(endedState.heroTrailerFullscreenMode)
        assertFalse(endedState.fullscreenTrailerTextTimedOut)
        assertFalse(endedState.heroFullscreenHintTimedOut)
        assertEquals("row-1", focusRestore?.first)
        assertEquals(2, focusRestore?.second)
    }

    @Test
    fun `external manual trailer takeover clears active modern home trailer session`() {
        val clearedState = handleModernHomeTrailerInterruptedByExternalTakeover(
            ModernHomeTrailerEndedState(
                unlockedTrailerFocusKey = "row-1:item-2",
                pendingHeroTrailerFocusKey = "row-1:item-2",
                heroTrailerFullscreenMode = true,
                fullscreenTrailerTextTimedOut = true,
                heroFullscreenHintTimedOut = true
            )
        )

        assertNull(clearedState.unlockedTrailerFocusKey)
        assertNull(clearedState.pendingHeroTrailerFocusKey)
        assertFalse(clearedState.heroTrailerFullscreenMode)
        assertFalse(clearedState.fullscreenTrailerTextTimedOut)
        assertFalse(clearedState.heroFullscreenHintTimedOut)
    }

    @Test
    fun `hero fullscreen hint is shown only while hero trailer is playing and timer is active`() {
        assertTrue(
            shouldShowModernHomeHeroFullscreenHint(
                heroTrailerPlaying = true,
                fullscreenTrailerActive = false,
                hintTimedOut = false
            )
        )
        assertFalse(
            shouldShowModernHomeHeroFullscreenHint(
                heroTrailerPlaying = false,
                fullscreenTrailerActive = false,
                hintTimedOut = false
            )
        )
        assertFalse(
            shouldShowModernHomeHeroFullscreenHint(
                heroTrailerPlaying = true,
                fullscreenTrailerActive = true,
                hintTimedOut = false
            )
        )
        assertFalse(
            shouldShowModernHomeHeroFullscreenHint(
                heroTrailerPlaying = true,
                fullscreenTrailerActive = false,
                hintTimedOut = true
            )
        )
    }

    @Test
    fun `hero fullscreen hint end padding compensates for hero surface overflow`() {
        assertEquals(76.dp, resolveModernHomeHeroFullscreenHintEndPadding(fullscreenTrailerActive = false))
        assertEquals(20.dp, resolveModernHomeHeroFullscreenHintEndPadding(fullscreenTrailerActive = true))
    }
}
