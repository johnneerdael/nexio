package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class ModernHomeTrailerBehaviorTest {

    @Test
    fun `home trailer autoplay remains locked while screensaver is visible`() {
        assertFalse(
            shouldUnlockModernHomeTrailerAutoplay(
                trailerEnabled = true,
                autoplayEnabled = true,
                screensaverVisible = true,
                selectionStillFocused = true,
                lifecycleResumed = true
            )
        )
    }

    @Test
    fun `home trailer autoplay unlocks after delay when enabled and resumed`() {
        assertTrue(
            shouldUnlockModernHomeTrailerAutoplay(
                trailerEnabled = true,
                autoplayEnabled = true,
                screensaverVisible = false,
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
                alreadyRetriedAfterUnlock = false
            )
        )
    }

    @Test
    fun `focused trailer preview does not request after autoplay unlock when metadata is missing`() {
        assertFalse(
            shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
                trailerPlaybackUnlocked = true,
                hasTrailerMetadataAvailable = false,
                hasResolvedPreview = false,
                hasResolvedExternalPreview = false,
                isCurrentlyLoading = false,
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
                alreadyRetriedAfterUnlock = true
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
        assertTrue(
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
}
