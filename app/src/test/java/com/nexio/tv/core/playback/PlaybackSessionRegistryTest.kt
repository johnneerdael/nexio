package com.nexio.tv.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionRegistryTest {
    private val registry = PlaybackSessionRegistry()

    private fun ctx(profileId: Int, sessionId: String = "session-$profileId") =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = sessionId,
            startedAtEpochMs = 1L
        )

    @Test
    fun `starts empty`() {
        assertNull(registry.activeOwner())
        assertTrue(registry.isIdle())
    }

    @Test
    fun `register exposes active owner`() {
        val context = ctx(1)
        registry.register(context)
        assertEquals(context, registry.activeOwner())
        assertTrue(!registry.isIdle())
    }

    @Test
    fun `unregister clears active owner only when token matches`() {
        val context = ctx(1)
        val token = registry.register(context)
        registry.unregister(token = "wrong-token")
        assertEquals(context, registry.activeOwner())
        registry.unregister(token = token)
        assertNull(registry.activeOwner())
    }

    @Test
    fun `register replaces previous owner and returns new token`() {
        val first = registry.register(ctx(1))
        val second = registry.register(ctx(2))
        assertTrue(first != second)
        assertEquals(2, registry.activeOwner()?.ownerProfileId)
    }
}
