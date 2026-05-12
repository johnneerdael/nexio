package com.nexio.tv.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingIdBundleTest {

    @Test
    fun `matches when imdb ids match`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1")
        val b = ContinueWatchingIdBundle(imdb = "tt1", tmdb = "999")
        assertTrue(a.matches(b))
        assertTrue(b.matches(a))
    }

    @Test
    fun `matches when tmdb ids match`() {
        val a = ContinueWatchingIdBundle(tmdb = "1396")
        val b = ContinueWatchingIdBundle(tmdb = "1396")
        assertTrue(a.matches(b))
    }

    @Test
    fun `matches when tvdb ids match`() {
        val a = ContinueWatchingIdBundle(tvdb = "81189")
        val b = ContinueWatchingIdBundle(tvdb = "81189")
        assertTrue(a.matches(b))
    }

    @Test
    fun `no match when ids disjoint`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1")
        val b = ContinueWatchingIdBundle(imdb = "tt2")
        assertFalse(a.matches(b))
    }

    @Test
    fun `no match when both bundles empty`() {
        assertFalse(ContinueWatchingIdBundle().matches(ContinueWatchingIdBundle()))
    }

    @Test
    fun `no match when only one side has ids`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1")
        val b = ContinueWatchingIdBundle()
        assertFalse(a.matches(b))
        assertFalse(b.matches(a))
    }

    @Test
    fun `same provider with different ids does not match`() {
        // tmdb 1 vs tmdb 2 — same provider, different values
        val a = ContinueWatchingIdBundle(imdb = "tt1", tmdb = "1")
        val b = ContinueWatchingIdBundle(imdb = "tt2", tmdb = "2")
        // Should not match on imdb (different) or tmdb (different); empty intersection
        assertFalse(a.matches(b))
    }

    @Test
    fun `episode bundles only match when season and episode match`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 5)
        val b = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 6)
        assertFalse(a.matches(b))
    }

    @Test
    fun `episode bundle matches when season and episode and any id match`() {
        val a = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 5)
        val b = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 5)
        assertTrue(a.matches(b))
    }

    @Test
    fun `movie bundle (no season episode) matches episode bundle only when both have no season episode`() {
        val movie = ContinueWatchingIdBundle(imdb = "tt1")
        val episode = ContinueWatchingIdBundle(imdb = "tt1", season = 1, episode = 1)
        assertFalse(movie.matches(episode))
    }

    @Test
    fun `priorityKey prefers imdb`() {
        val ids = ContinueWatchingIdBundle(
            imdb = "tt1", tmdb = "1", tvdb = "10", kitsu = "x", mal = "z",
        )
        assertEquals("imdb:tt1", ids.priorityKey())
    }

    @Test
    fun `priorityKey falls back to tmdb then tvdb then kitsu`() {
        assertEquals("tmdb:1", ContinueWatchingIdBundle(tmdb = "1", tvdb = "10").priorityKey())
        assertEquals("tvdb:10", ContinueWatchingIdBundle(tvdb = "10", kitsu = "x").priorityKey())
        assertEquals("kitsu:x", ContinueWatchingIdBundle(kitsu = "x").priorityKey())
    }

    @Test
    fun `priorityKey is null when bundle is empty`() {
        assertNull(ContinueWatchingIdBundle().priorityKey())
    }
}
