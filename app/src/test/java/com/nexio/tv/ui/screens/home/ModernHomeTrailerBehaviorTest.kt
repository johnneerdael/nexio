package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
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
        assertTrue(shouldDismissModernHomeTrailerOnBack(heroTrailerActive = true, expandedCardTrailerActive = false))
        assertTrue(shouldDismissModernHomeTrailerOnBack(heroTrailerActive = false, expandedCardTrailerActive = true))
        assertFalse(shouldDismissModernHomeTrailerOnBack(heroTrailerActive = false, expandedCardTrailerActive = false))
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
}
