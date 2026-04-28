package com.nexio.tv.data.integration.railpreview

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RailPreviewMapperTest {
    @Test
    fun `simkl image fragments become full urls`() {
        assertEquals(
            "https://simkl.in/posters/52/52598920_m.jpg",
            simklImageUrl("52/52598920_m.jpg")
        )
    }

    @Test
    fun `stable item key prefers imdb before source raw id`() {
        assertEquals(
            "movie:imdb:tt1375666",
            railPreviewItemKey(ContentType.MOVIE, ProviderIds(imdb = "tt1375666"), "simkl:123")
        )
    }

    @Test
    fun `tmdb image paths become full urls with default size`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w500/poster.jpg",
            tmdbImageUrl("/poster.jpg")
        )
    }

    @Test
    fun `tmdb image paths use requested size`() {
        assertEquals(
            "https://image.tmdb.org/t/p/w780/poster.jpg",
            tmdbImageUrl("poster.jpg", size = "w780")
        )
    }

    @Test
    fun `tmdb image helper preserves full urls`() {
        assertEquals(
            "https://cdn.example/poster.jpg",
            tmdbImageUrl("https://cdn.example/poster.jpg")
        )
    }

    @Test
    fun `simkl image helper avoids malformed double slash`() {
        assertEquals(
            "https://simkl.in/posters/52/52598920_m.jpg",
            simklImageUrl("/52/52598920_m.jpg")
        )
    }

    @Test
    fun `simkl image helper preserves full urls`() {
        assertEquals(
            "https://cdn.example/poster.jpg",
            simklImageUrl("https://cdn.example/poster.jpg")
        )
    }

    @Test
    fun `image helpers return null for null or blank values`() {
        assertNull(tmdbImageUrl(null))
        assertNull(tmdbImageUrl("   "))
        assertNull(simklImageUrl(null))
        assertNull(simklImageUrl("   "))
    }

    @Test
    fun `stable payload hash is deterministic and changes with input`() {
        assertEquals(stablePayloadHash("abc"), stablePayloadHash("abc"))
        assertNotEquals(stablePayloadHash("abc"), stablePayloadHash("abcd"))
    }

    @Test
    fun `year from date reads valid years and rejects invalid values`() {
        assertEquals(2010, yearFromDate("2010-07-16"))
        assertNull(yearFromDate("abcd-07-16"))
        assertNull(yearFromDate("   "))
        assertNull(yearFromDate(null))
    }

    @Test
    fun `first non blank returns the first trimmed value`() {
        assertEquals("Title", firstNonBlank(null, " ", "Title"))
        assertNull(firstNonBlank(null, " ", ""))
    }
}
