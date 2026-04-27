package com.nexio.tv.core.playback

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.ProviderAccountRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlaybackOwnerContextTest {
    @Test
    fun `constructs with required fields`() {
        val context = PlaybackOwnerContext(
            ownerProfileId = 1,
            ownerSessionId = "profile:1:abc",
            traktAccount = ProviderAccountRef(IntegrationProvider.TRAKT, "trakt-hash", null),
            simklAccount = null,
            startedAtEpochMs = 1_700_000_000_000L
        )
        assertEquals(1, context.ownerProfileId)
        assertEquals("profile:1:abc", context.ownerSessionId)
        assertEquals("trakt-hash", context.traktAccount?.credentialHash)
    }

    @Test
    fun `rejects non-positive profileId`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackOwnerContext(
                ownerProfileId = 0,
                ownerSessionId = "x",
                traktAccount = null,
                simklAccount = null,
                startedAtEpochMs = 1L
            )
        }
    }

    @Test
    fun `rejects blank sessionId`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackOwnerContext(
                ownerProfileId = 1,
                ownerSessionId = "",
                traktAccount = null,
                simklAccount = null,
                startedAtEpochMs = 1L
            )
        }
    }

    @Test
    fun `rejects non-positive startedAtEpochMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            PlaybackOwnerContext(
                ownerProfileId = 1,
                ownerSessionId = "x",
                traktAccount = null,
                simklAccount = null,
                startedAtEpochMs = 0L
            )
        }
    }
}
