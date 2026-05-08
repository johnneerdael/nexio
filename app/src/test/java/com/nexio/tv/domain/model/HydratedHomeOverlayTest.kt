package com.nexio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun `display hash distinguishes single genre containing separator from multiple genres`() {
        val singleGenre = HomeDisplayMetadata(genres = listOf("A|B")).hydratedHomeDisplayHash()
        val multipleGenres = HomeDisplayMetadata(genres = listOf("A", "B")).hydratedHomeDisplayHash()

        assertNotEquals(singleGenre, multipleGenres)
    }

    @Test
    fun `display hash differs when only thumbnail differs`() {
        val withoutThumbnail = HomeDisplayMetadata(title = "Same").hydratedHomeDisplayHash()
        val withThumbnail = HomeDisplayMetadata(
            title = "Same",
            thumbnail = "https://example.com/thumb.jpg"
        ).hydratedHomeDisplayHash()

        assertNotEquals(withoutThumbnail, withThumbnail)
    }

    @Test
    fun `display hash distinguishes field separator inside text from adjacent field values`() {
        val separator = "\u001F"
        val embeddedSeparator = HomeDisplayMetadata(title = "A${separator}B").hydratedHomeDisplayHash()
        val adjacentFields = HomeDisplayMetadata(title = "A", logo = "B").hydratedHomeDisplayHash()

        assertNotEquals(embeddedSeparator, adjacentFields)
    }

    @Test
    fun `display hash defaults from fields`() {
        val fields = HomeDisplayMetadata(title = "Fight Club")
        val overlay = HydratedHomeOverlay(
            overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
            itemKey = "movie:tmdb:550",
            canonicalProvider = ProviderId.TMDB,
            canonicalId = "550",
            imdbId = "tt0137523",
            contentType = ContentType.MOVIE,
            languageTag = "en",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            updatedAtMs = 1_000L,
            staleAtMs = 2_000L,
            expiresAtMs = 3_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )

        assertEquals(fields.hydratedHomeDisplayHash(), overlay.displayHash)
    }

    @Test
    fun `display hash must match fields when supplied`() {
        try {
            hydratedOverlay(
                fields = HomeDisplayMetadata(title = "Fight Club"),
                displayHash = HomeDisplayMetadata(title = "Different").hydratedHomeDisplayHash()
            )
            fail("Expected mismatched displayHash to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("displayHash"))
        }
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
        expiresAtMs: Long,
        fields: HomeDisplayMetadata = HomeDisplayMetadata(title = "Fight Club"),
        displayHash: String = fields.hydratedHomeDisplayHash()
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = fields,
        fieldTrace = listOf(
            HydratedHomeFieldTrace(
                field = "TITLE",
                selectedProvider = "TMDB",
                sourceRole = "PRIMARY"
            )
        ),
        displayHash = displayHash,
        updatedAtMs = updatedAtMs,
        staleAtMs = staleAtMs,
        expiresAtMs = expiresAtMs,
        state = HomeItemHydrationState.CANONICAL_READY
    )

    private fun hydratedOverlay(
        fields: HomeDisplayMetadata,
        displayHash: String = fields.hydratedHomeDisplayHash()
    ) = HydratedHomeOverlay(
        overlayKey = "canonical:TMDB:550:type:MOVIE:lang:en:policy:1",
        itemKey = "movie:tmdb:550",
        canonicalProvider = ProviderId.TMDB,
        canonicalId = "550",
        imdbId = "tt0137523",
        contentType = ContentType.MOVIE,
        languageTag = "en",
        policyVersion = 1,
        fields = fields,
        fieldTrace = emptyList(),
        displayHash = displayHash,
        updatedAtMs = 1_000L,
        staleAtMs = 2_000L,
        expiresAtMs = 3_000L,
        state = HomeItemHydrationState.CANONICAL_READY
    )
}
