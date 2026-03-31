package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernHomeTrailerBehaviorTest {

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
}
