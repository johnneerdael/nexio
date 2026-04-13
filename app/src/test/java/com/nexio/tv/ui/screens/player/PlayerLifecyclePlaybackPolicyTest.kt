package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerLifecyclePlaybackPolicyTest {

    @Test
    fun `lifecycle resume restores autoplay when pause happened before first frame`() {
        assertTrue(
            shouldResumeAutoplayAfterLifecyclePause(
                hadAutoplayIntent = true,
                hasRenderedFirstFrame = false,
                userPausedManually = false
            )
        )
    }

    @Test
    fun `lifecycle resume does not override manual pause`() {
        assertFalse(
            shouldResumeAutoplayAfterLifecyclePause(
                hadAutoplayIntent = true,
                hasRenderedFirstFrame = false,
                userPausedManually = true
            )
        )
    }

    @Test
    fun `lifecycle resume does not auto resume after playback was visible`() {
        assertFalse(
            shouldResumeAutoplayAfterLifecyclePause(
                hadAutoplayIntent = true,
                hasRenderedFirstFrame = true,
                userPausedManually = false
            )
        )
    }

    @Test
    fun `lifecycle resume requires existing autoplay intent`() {
        assertFalse(
            shouldResumeAutoplayAfterLifecyclePause(
                hadAutoplayIntent = false,
                hasRenderedFirstFrame = false,
                userPausedManually = false
            )
        )
    }
}
