package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.UnifiedWatchlistMembershipConfidence
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import com.nexio.tv.domain.model.UnifiedWatchlistSourceItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedWatchlistMembershipReducerTest {
    @Test
    fun `strong imdb match merges into one provider-neutral movie`() {
        val rows = UnifiedWatchlistMembershipReducer.reduce(
            listOf(
                UnifiedWatchlistSourceItem.traktMovie("tt2543164", tmdb = 329865),
                UnifiedWatchlistSourceItem.simklMovie("tt2543164", tmdb = 329865)
            )
        )

        assertEquals(1, rows.size)
        assertEquals(setOf(UnifiedWatchlistSource.TRAKT, UnifiedWatchlistSource.SIMKL), rows.single().presentIn)
        assertEquals(UnifiedWatchlistMembershipConfidence.STRONG, rows.single().confidence)
    }

    @Test
    fun `episode input collapses to show level`() {
        val rows = UnifiedWatchlistMembershipReducer.reduce(
            listOf(
                UnifiedWatchlistSourceItem.traktEpisode(showTmdb = 1399, season = 1, episode = 1)
            )
        )

        assertEquals(1, rows.size)
        assertEquals(ContentType.SERIES, rows.single().contentType)
        assertEquals("series:tmdb:1399", rows.single().authorityKey)
        assertEquals(null, rows.single().season)
        assertEquals(null, rows.single().episode)
    }

    @Test
    fun `weak title year fallback is marked low confidence`() {
        val rows = UnifiedWatchlistMembershipReducer.reduce(
            listOf(
                UnifiedWatchlistSourceItem.localTitle("movie", "Solaris", 1972),
                UnifiedWatchlistSourceItem.traktTitle("movie", "Solaris", 1972)
            )
        )

        assertEquals(1, rows.size)
        assertEquals(UnifiedWatchlistMembershipConfidence.LOW, rows.single().confidence)
    }

    @Test
    fun `different media types do not merge on same tmdb value`() {
        val rows = UnifiedWatchlistMembershipReducer.reduce(
            listOf(
                UnifiedWatchlistSourceItem.traktMovie(imdb = null, tmdb = 100),
                UnifiedWatchlistSourceItem.simklSeries(tmdb = 100)
            )
        )

        assertEquals(2, rows.size)
        assertTrue(rows.any { it.contentType == ContentType.MOVIE && it.authorityKey == "movie:tmdb:100" })
        assertTrue(rows.any { it.contentType == ContentType.SERIES && it.authorityKey == "series:tmdb:100" })
    }

    @Test
    fun `membership keeps first available provider display metadata`() {
        val rows = UnifiedWatchlistMembershipReducer.reduce(
            listOf(
                UnifiedWatchlistSourceItem(
                    source = UnifiedWatchlistSource.TRAKT,
                    rawKey = "tt32820897",
                    contentType = ContentType.MOVIE,
                    title = "Demon Slayer",
                    year = 2025,
                    imdbId = "tt32820897",
                    tmdbId = 1311031,
                    poster = "nexio-artwork://decision/poster",
                    background = "https://image.tmdb.org/t/p/w1280/backdrop.jpg",
                    logo = "https://image.tmdb.org/t/p/w500/logo.png",
                    description = "The Corps are drawn into the Infinity Castle.",
                    imdbRating = 7.7f,
                    genres = listOf("Animation", "Action")
                ),
                UnifiedWatchlistSourceItem.simklMovie(
                    imdb = "tt32820897",
                    tmdb = 1311031,
                    title = "Demon Slayer"
                )
            )
        )

        val row = rows.single()
        assertEquals("nexio-artwork://decision/poster", row.poster)
        assertEquals("https://image.tmdb.org/t/p/w1280/backdrop.jpg", row.background)
        assertEquals("https://image.tmdb.org/t/p/w500/logo.png", row.logo)
        assertEquals("The Corps are drawn into the Infinity Castle.", row.description)
        assertEquals(7.7f, row.imdbRating)
        assertEquals(listOf("Animation", "Action"), row.genres)
    }
}
