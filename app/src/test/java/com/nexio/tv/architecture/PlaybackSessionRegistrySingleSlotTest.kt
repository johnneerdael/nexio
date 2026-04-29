package com.nexio.tv.architecture

import com.nexio.tv.core.playback.PlaybackOwnerContext
import com.nexio.tv.core.playback.PlaybackSessionRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * F-H-02 architecture pin: PlaybackSessionRegistry is intentionally single-slot. A second
 * register(...) overwrites the first; the first token's unregister becomes a no-op.
 *
 * If you need multi-VM concurrent registration (e.g. PIP + main player), migrate the registry
 * to ConcurrentHashMap<String, PlaybackOwnerContext> AND document the migration. Don't add a
 * second slot ad-hoc.
 */
class PlaybackSessionRegistrySingleSlotTest {

    private fun ownerContext(profileId: Int, sessionId: String): PlaybackOwnerContext =
        PlaybackOwnerContext(
            ownerProfileId = profileId,
            ownerSessionId = sessionId,
            startedAtEpochMs = 1L
        )

    @Test
    fun `second register overwrites first and activeOwner returns the latest`() {
        val registry = PlaybackSessionRegistry()
        registry.register(ownerContext(profileId = 1, sessionId = "a"))
        registry.register(ownerContext(profileId = 2, sessionId = "b"))

        assertEquals("activeOwner must return the latest (B)", 2, registry.activeOwner()?.ownerProfileId)
    }

    @Test
    fun `unregister of overwritten token is a noop`() {
        val registry = PlaybackSessionRegistry()
        val tokenA = registry.register(ownerContext(profileId = 1, sessionId = "a"))
        registry.register(ownerContext(profileId = 2, sessionId = "b"))

        registry.unregister(tokenA)  // tokenA was already overwritten by B — no-op

        assertEquals("activeOwner still B", 2, registry.activeOwner()?.ownerProfileId)
    }

    @Test
    fun `unregister of current token clears the slot`() {
        val registry = PlaybackSessionRegistry()
        val token = registry.register(ownerContext(profileId = 1, sessionId = "a"))
        registry.unregister(token)
        assertNull("slot empty after unregister", registry.activeOwner())
    }
}
