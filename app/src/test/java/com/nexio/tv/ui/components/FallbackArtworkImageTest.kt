package com.nexio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FallbackArtworkImageTest {
    @Test
    fun failedPrimaryWithoutFallbackShowsPlaceholder() {
        val state = fallbackArtworkImageState(
            model = "nexio-artwork://decision/missing",
            fallbackModel = null,
            failedPrimary = true,
            failedFallback = false
        )

        assertSame(FallbackArtworkImageState.Placeholder, state)
    }

    @Test
    fun nullPrimaryUsesFallbackModelImmediately() {
        val fallback = "https://image.tmdb.org/t/p/w500/fallback.jpg"

        val state = fallbackArtworkImageState(
            model = null,
            fallbackModel = fallback,
            failedPrimary = false,
            failedFallback = false
        )

        val image = state as FallbackArtworkImageState.Image
        assertEquals(fallback, image.model)
        assertTrue(image.isFallback)
    }

    @Test
    fun failedPrimaryUsesFallbackWhenAvailable() {
        val fallback = "https://image.tmdb.org/t/p/w500/fallback.jpg"

        val state = fallbackArtworkImageState(
            model = "nexio-artwork://decision/missing",
            fallbackModel = fallback,
            failedPrimary = true,
            failedFallback = false
        )

        val image = state as FallbackArtworkImageState.Image
        assertEquals(fallback, image.model)
        assertTrue(image.isFallback)
    }

    @Test
    fun primaryModelIsRenderedBeforeFallback() {
        val primary = "nexio-artwork://decision/available"

        val state = fallbackArtworkImageState(
            model = primary,
            fallbackModel = "https://image.tmdb.org/t/p/w500/fallback.jpg",
            failedPrimary = false,
            failedFallback = false
        )

        val image = state as FallbackArtworkImageState.Image
        assertEquals(primary, image.model)
        assertFalse(image.isFallback)
    }
}
