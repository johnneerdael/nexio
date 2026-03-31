package com.nexio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerRetryStateTest {

    @Test
    fun `shouldPreserveTransientRetryState only keeps retry budget for same stream`() {
        assertTrue(
            shouldPreserveTransientRetryState(
                preservedRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token-a")
                ),
                nextStreamUrl = "https://stream.example/one.m3u8",
                nextHeaders = mapOf("Authorization" to "token-a")
            )
        )
        assertFalse(
            shouldPreserveTransientRetryState(
                preservedRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token-a")
                ),
                nextStreamUrl = "https://stream.example/two.m3u8",
                nextHeaders = mapOf("Authorization" to "token-a")
            )
        )
        assertFalse(
            shouldPreserveTransientRetryState(
                preservedRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token-a")
                ),
                nextStreamUrl = "https://stream.example/one.m3u8",
                nextHeaders = mapOf("Authorization" to "token-b")
            )
        )
    }

    @Test
    fun `shouldRunDeferredReinitialize skips stale deferred retries after stream switch`() {
        assertTrue(
            shouldRunDeferredReinitialize(
                scheduledRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token")
                ),
                currentStreamUrl = "https://stream.example/one.m3u8",
                currentHeaders = mapOf("Authorization" to "token"),
                scheduledPlaybackSessionIsCurrent = true
            )
        )
        assertFalse(
            shouldRunDeferredReinitialize(
                scheduledRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token")
                ),
                currentStreamUrl = "https://stream.example/two.m3u8",
                currentHeaders = mapOf("Authorization" to "token"),
                scheduledPlaybackSessionIsCurrent = true
            )
        )
        assertFalse(
            shouldRunDeferredReinitialize(
                scheduledRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token-a")
                ),
                currentStreamUrl = "https://stream.example/one.m3u8",
                currentHeaders = mapOf("Authorization" to "token-b"),
                scheduledPlaybackSessionIsCurrent = true
            )
        )
        assertFalse(
            shouldRunDeferredReinitialize(
                scheduledRequest = DeferredPlayerRequestIdentity(
                    streamUrl = "https://stream.example/one.m3u8",
                    headers = mapOf("Authorization" to "token-a")
                ),
                currentStreamUrl = "https://stream.example/one.m3u8",
                currentHeaders = mapOf("Authorization" to "token-a"),
                scheduledPlaybackSessionIsCurrent = false
            )
        )
    }
}
