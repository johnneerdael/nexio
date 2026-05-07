package com.nexio.tv.core.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TraceHashTest {
    @Test
    fun `same salt and value yield same hash`() {
        assertEquals(TraceHash.of("salt", "1"), TraceHash.of("salt", "1"))
    }

    @Test
    fun `different salt yields different hash`() {
        assertNotEquals(TraceHash.of("a", "1"), TraceHash.of("b", "1"))
    }

    @Test
    fun `hash is exactly 12 chars`() {
        assertEquals(12, TraceHash.of("salt", "anything").length)
    }

    @Test
    fun `metadata event hashes are scoped by active trace session`() {
        val firstSession = TraceMetadataEvents(NoopRuntimeTraceSink) { "trace-session-a" }
        val secondSession = TraceMetadataEvents(NoopRuntimeTraceSink) { "trace-session-b" }

        assertNotEquals(
            firstSession.hashForActiveSession("home-rating-artwork", "tmdb:94997"),
            secondSession.hashForActiveSession("home-rating-artwork", "tmdb:94997")
        )
    }
}
