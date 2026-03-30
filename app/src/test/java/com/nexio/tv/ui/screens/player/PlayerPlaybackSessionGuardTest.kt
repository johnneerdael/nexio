package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlaybackSessionGuardTest {

    @Test
    fun beginPlaybackSession_acceptsCallbacksForActiveSession() {
        val guard = PlayerPlaybackSessionGuard()

        val sessionId = guard.beginPlaybackSession()

        assertTrue(guard.shouldHandleCallback(sessionId))
    }

    @Test
    fun beginPlayerExit_rejectsCallbacksForActiveSession() {
        val guard = PlayerPlaybackSessionGuard()
        val sessionId = guard.beginPlaybackSession()

        guard.beginPlayerExit()

        assertFalse(guard.shouldHandleCallback(sessionId))
    }

    @Test
    fun beginPlaybackSession_rejectsCallbacksFromSupersededSession() {
        val guard = PlayerPlaybackSessionGuard()
        val firstSessionId = guard.beginPlaybackSession()

        val secondSessionId = guard.beginPlaybackSession()

        assertFalse(guard.shouldHandleCallback(firstSessionId))
        assertTrue(guard.shouldHandleCallback(secondSessionId))
    }

    @Test
    fun onPlayerReleased_rejectsCallbacksForReleasedSession() {
        val guard = PlayerPlaybackSessionGuard()
        val sessionId = guard.beginPlaybackSession()

        guard.onPlayerReleased()

        assertFalse(guard.shouldHandleCallback(sessionId))
    }
}
