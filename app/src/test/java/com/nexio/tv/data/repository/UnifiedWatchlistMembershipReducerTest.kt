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
}
