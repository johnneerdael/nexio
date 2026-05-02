package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HydratedHomeOverlayTest {
    @Test
    fun `overlay key includes canonical identity language type and policy`() {
        val key = hydratedHomeOverlayKey(
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1
        )

        assertEquals("canonical:TMDB:550:type:MOVIE:lang:en-US:policy:1", key)
    }

    @Test
    fun `display hash changes when displayed fields change`() {
        val first = HomeDisplayMetadata(title = "Preview", poster = null).hydratedHomeDisplayHash()
        val second = HomeDisplayMetadata(title = "Canonical", poster = null).hydratedHomeDisplayHash()

        assertNotEquals(first, second)
    }

    @Test
    fun `freshness uses stale and expiry timestamps`() {
        val overlay = hydratedOverlay(
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L
        )

        assertFalse(overlay.isStale(nowMs = 1_500L))
        assertTrue(overlay.isStale(nowMs = 2_500L))
        assertFalse(overlay.isExpired(nowMs = 2_500L))
        assertTrue(overlay.isExpired(nowMs = 3_500L))
    }

    private fun hydratedOverlay(
        updatedAtMs: Long,
        staleAtMs: Long,
        expiresAtMs: Long
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = HomeDisplayMetadata(title = "Fight Club"),
        fieldTrace = listOf(
            HydratedHomeFieldTrace(
                field = "TITLE",
                selectedProvider = "TMDB",
                sourceRole = "PRIMARY"
            )
        ),
        displayHash = HomeDisplayMetadata(title = "Fight Club").hydratedHomeDisplayHash(),
        updatedAtMs = updatedAtMs,
        staleAtMs = staleAtMs,
        expiresAtMs = expiresAtMs,
        state = HomeItemHydrationState.CANONICAL_READY
    )
}
