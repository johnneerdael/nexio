package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.UnifiedWatchlistMembership
import com.nexio.tv.domain.model.UnifiedWatchlistMembershipConfidence
import com.nexio.tv.domain.model.UnifiedWatchlistSource
import com.nexio.tv.domain.model.UnifiedWatchlistSourceRef
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchlistAutoRemoveCoordinatorTest {
    @Test
    fun `plans one remove per provider source ref`() {
        val membership = UnifiedWatchlistMembership(
            authorityKey = "movie:tmdb:550",
            contentType = ContentType.MOVIE,
            presentIn = setOf(UnifiedWatchlistSource.TRAKT, UnifiedWatchlistSource.SIMKL),
            sourceRefs = listOf(
                UnifiedWatchlistSourceRef(UnifiedWatchlistSource.TRAKT, rawKey = "trakt-raw", listKey = "watchlist"),
                UnifiedWatchlistSourceRef(UnifiedWatchlistSource.SIMKL, rawKey = "simkl-raw", listKey = "simkl:plantowatch")
            ),
            confidence = UnifiedWatchlistMembershipConfidence.STRONG,
            title = "Fight Club",
            year = 1999,
            tmdbId = 550,
            imdbId = "tt0137523"
        )

        val plans = WatchlistAutoRemoveCoordinator.planRemoveOperations(
            membership = membership,
            nowMs = 1_000L,
            lastSentByDedupeKey = emptyMap()
        )

        assertEquals(
            listOf(
                WatchlistAutoRemoveCoordinator.RemovePlan(UnifiedWatchlistSource.TRAKT, "trakt-raw", "movie:tmdb:550"),
                WatchlistAutoRemoveCoordinator.RemovePlan(UnifiedWatchlistSource.SIMKL, "simkl-raw", "movie:tmdb:550")
            ),
            plans
        )
    }

    @Test
    fun `suppresses duplicate remove operations inside ttl`() {
        val membership = UnifiedWatchlistMembership(
            authorityKey = "movie:tmdb:550",
            contentType = ContentType.MOVIE,
            presentIn = setOf(UnifiedWatchlistSource.TRAKT),
            sourceRefs = listOf(
                UnifiedWatchlistSourceRef(UnifiedWatchlistSource.TRAKT, rawKey = "trakt-raw", listKey = "watchlist")
            ),
            confidence = UnifiedWatchlistMembershipConfidence.STRONG,
            tmdbId = 550
        )

        val plans = WatchlistAutoRemoveCoordinator.planRemoveOperations(
            membership = membership,
            nowMs = 30_000L,
            lastSentByDedupeKey = mapOf("movie:tmdb:550|TRAKT|trakt-raw" to 20_000L)
        )

        assertEquals(emptyList<WatchlistAutoRemoveCoordinator.RemovePlan>(), plans)
    }

    @Test
    fun `skips non movie memberships like CrossWatch auto remove`() {
        val membership = UnifiedWatchlistMembership(
            authorityKey = "series:tmdb:1399",
            contentType = ContentType.SERIES,
            presentIn = setOf(UnifiedWatchlistSource.TRAKT),
            sourceRefs = listOf(
                UnifiedWatchlistSourceRef(UnifiedWatchlistSource.TRAKT, rawKey = "trakt-raw", listKey = "watchlist")
            ),
            confidence = UnifiedWatchlistMembershipConfidence.STRONG,
            tmdbId = 1399
        )

        val plans = WatchlistAutoRemoveCoordinator.planRemoveOperations(
            membership = membership,
            nowMs = 1_000L,
            lastSentByDedupeKey = emptyMap()
        )

        assertEquals(emptyList<WatchlistAutoRemoveCoordinator.RemovePlan>(), plans)
    }
}
