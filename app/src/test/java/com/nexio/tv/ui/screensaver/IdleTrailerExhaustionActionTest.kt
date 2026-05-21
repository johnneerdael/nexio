package com.nexio.tv.ui.screensaver

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Plan: Bug B — Task B3.
 *
 * Pure-logic tests for `decideIdleTrailerExhaustionAction`. Decides whether the
 * screensaver overlay should fall back to IMAGE mode or dismiss entirely after
 * `resolveNextIdleTrailerPlayback` exhausts the candidate pool (both attempts —
 * with and without `failedPlaybackKeys` reset — returned null).
 */
class IdleTrailerExhaustionActionTest {

    @Test
    fun `at least one successful playback then exhausted falls back to IMAGE`() {
        val action = decideIdleTrailerExhaustionAction(
            hadAtLeastOneSuccessfulPlayback = true,
            secondAttemptResolvedAny = false
        )
        assertEquals(IdleTrailerExhaustionAction.FALLBACK_TO_IMAGE, action)
    }

    @Test
    fun `no successful playback and second attempt returns null dismisses entirely`() {
        val action = decideIdleTrailerExhaustionAction(
            hadAtLeastOneSuccessfulPlayback = false,
            secondAttemptResolvedAny = false
        )
        assertEquals(IdleTrailerExhaustionAction.DISMISS, action)
    }

    @Test
    fun `successful playback present even when second attempt resolved still falls back to IMAGE`() {
        // Defensive: if the second attempt resolved something (i.e. exhaustion
        // was a transient state), the overlay caller is expected to handle
        // playback continuation directly; we still nominate IMAGE fallback as
        // the safe default so a dead-screen is never reached.
        val action = decideIdleTrailerExhaustionAction(
            hadAtLeastOneSuccessfulPlayback = true,
            secondAttemptResolvedAny = true
        )
        assertEquals(IdleTrailerExhaustionAction.FALLBACK_TO_IMAGE, action)
    }
}
