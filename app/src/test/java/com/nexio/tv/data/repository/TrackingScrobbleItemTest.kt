package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ProviderIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackingScrobbleItemTest {

    @Test
    fun `movie carries hydratedIds when supplied`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "1")
        val movie = TrackingScrobbleItem.Movie(
            contentId = "tmdb:1",
            title = "x",
            year = 2000,
            hydratedIds = ids,
        )
        assertEquals(ids, movie.hydratedIds)
    }

    @Test
    fun `movie hydratedIds defaults to null`() {
        val movie = TrackingScrobbleItem.Movie(contentId = "tmdb:1", title = "x", year = 2000)
        assertNull(movie.hydratedIds)
    }

    @Test
    fun `episode carries hydratedIds when supplied`() {
        val ids = ProviderIds(imdb = "tt1", tmdb = "1", tvdb = "10")
        val ep = TrackingScrobbleItem.Episode(
            contentId = "tmdb:1",
            showTitle = "x",
            showYear = 2000,
            season = 1,
            number = 1,
            episodeTitle = "p",
            hydratedIds = ids,
        )
        assertEquals(ids, ep.hydratedIds)
    }

    @Test
    fun `episode hydratedIds defaults to null`() {
        val ep = TrackingScrobbleItem.Episode(
            contentId = "tmdb:1",
            showTitle = "x",
            showYear = 2000,
            season = 1,
            number = 1,
            episodeTitle = "p",
        )
        assertNull(ep.hydratedIds)
    }

    @Test
    fun `withHydratedIds returns Movie copy with new ids`() {
        val original = TrackingScrobbleItem.Movie(contentId = "tt1", title = "x", year = 2000)
        val ids = ProviderIds(imdb = "tt1", tmdb = "1")
        val updated = original.withHydratedIds(ids)
        check(updated is TrackingScrobbleItem.Movie)
        assertEquals(ids, updated.hydratedIds)
        assertEquals("x", updated.title)
        assertEquals(2000, updated.year)
        assertEquals("tt1", updated.contentId)
    }

    @Test
    fun `withHydratedIds returns Episode copy with new ids`() {
        val original = TrackingScrobbleItem.Episode(
            contentId = "tt1", showTitle = "x", showYear = 2000,
            season = 5, number = 14, episodeTitle = "p",
        )
        val ids = ProviderIds(imdb = "tt1", tmdb = "1", tvdb = "10")
        val updated = original.withHydratedIds(ids)
        check(updated is TrackingScrobbleItem.Episode)
        assertEquals(ids, updated.hydratedIds)
        assertEquals(5, updated.season)
        assertEquals(14, updated.number)
        assertEquals("p", updated.episodeTitle)
    }

    @Test
    fun `withHydratedIds nullable clears previous ids`() {
        val original = TrackingScrobbleItem.Movie(
            contentId = "tt1", title = "x", year = 2000,
            hydratedIds = ProviderIds(imdb = "tt1"),
        )
        val updated = original.withHydratedIds(null)
        assertNull(updated.hydratedIds)
    }
}
