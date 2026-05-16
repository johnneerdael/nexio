package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContinueWatchingCanonicalizationTest {

    @Test
    fun `unknown-date next-up is not eligible for main feed`() {
        val entry = nextUpEntry(firstAired = null, firstAiredMs = 0L, tvdbAvailabilityInstantMs = null)

        assertFalse(ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs = 10_000L))
    }

    @Test
    fun `future-dated next-up has a scheduled trigger but is not main-feed eligible`() {
        val futureAiredMs = 20_000L
        val entry = nextUpEntry(firstAiredMs = futureAiredMs, tvdbAvailabilityInstantMs = null)

        assertEquals(futureAiredMs, ContinueWatchingCanonicalization.pendingTriggerMs(entry))
        assertFalse(ContinueWatchingCanonicalization.isMainFeedAiredNextUp(entry, nowMs = 10_000L))
    }

    @Test
    fun `watched anchor suppresses same and earlier canonical coordinates but not later coordinates`() {
        val anchors = listOf(
            ContinueWatchingWatchedAnchor(
                lookupKeys = setOf("tvdb:430780", "series:tvdb:430780"),
                season = 1,
                episode = 7,
                lastWatchedMs = 50_000L
            )
        )

        assertTrue(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = setOf("series:tvdb:430780"),
                season = 1,
                episode = 7,
                updatedAtMs = 60_000L,
                anchors = anchors
            )
        )
        assertTrue(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = setOf("tvdb:430780"),
                season = 1,
                episode = 6,
                updatedAtMs = 60_000L,
                anchors = anchors
            )
        )
        assertFalse(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = setOf("tvdb:430780"),
                season = 1,
                episode = 8,
                updatedAtMs = 50_000L,
                anchors = anchors
            )
        )
        assertFalse(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = setOf("tvdb:430780"),
                season = 1,
                episode = 8,
                updatedAtMs = 60_000L,
                anchors = anchors
            )
        )
    }

    @Test
    fun `coordinate-less stale row is suppressed by watched anchor timestamp`() {
        val anchors = listOf(
            ContinueWatchingWatchedAnchor(
                lookupKeys = setOf("tvdb:430780", "series:tvdb:430780"),
                season = 1,
                episode = 7,
                lastWatchedMs = 50_000L
            )
        )

        assertTrue(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = setOf("tvdb:430780"),
                season = null,
                episode = null,
                updatedAtMs = 50_000L,
                anchors = anchors
            )
        )
    }

    @Test
    fun `completed progress creates provider alias anchor for tvdb 430780 S1E7`() {
        val anchors = ContinueWatchingCanonicalization.watchedAnchorsFromProgress(
            listOf(
                watchProgress(
                    contentId = "series:tvdb:430780",
                    season = 1,
                    episode = 7,
                    lastWatched = 50_000L
                )
            )
        )

        assertEquals(1, anchors.size)
        assertEquals(setOf("series:tvdb:430780", "tvdb:430780"), anchors.single().lookupKeys)
        assertEquals(1, anchors.single().season)
        assertEquals(7, anchors.single().episode)
        assertEquals(50_000L, anchors.single().lastWatchedMs)
    }

    @Test
    fun `raw content id lookup keys normalize typed and bare provider ids`() {
        assertEquals(
            setOf("series:tvdb:430780", "tvdb:430780"),
            ContinueWatchingCanonicalization.lookupKeysForRawContentId("series:tvdb:430780")
        )
        assertEquals(
            setOf("movie:imdb:tt1234567", "imdb:tt1234567", "tt1234567"),
            ContinueWatchingCanonicalization.lookupKeysForRawContentId("movie:imdb:tt1234567")
        )
        assertEquals(
            setOf("tvdb:430780"),
            ContinueWatchingCanonicalization.lookupKeysForRawContentId("tvdb:430780")
        )
        assertEquals(
            setOf("imdb:tt7654321", "tt7654321"),
            ContinueWatchingCanonicalization.lookupKeysForRawContentId("tt7654321")
        )
    }

    @Test
    fun `provider-first typed tvdb id uses numeric payload`() {
        val keys = ContinueWatchingCanonicalization.lookupKeysForRawContentId("tvdb:series:393268")

        assertTrue(keys.contains("tvdb:393268"))
        assertFalse(keys.contains("tvdb:series"))
    }

    @Test
    fun `typed trakt ids do not emit overlapping bare aliases`() {
        val showKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId("trakt:show:42")
        val movieKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId("trakt:movie:42")

        assertFalse(showKeys.contains("trakt:42"))
        assertFalse(movieKeys.contains("trakt:42"))
        assertTrue(showKeys.intersect(movieKeys).isEmpty())
    }

    @Test
    fun `completed tmdb movie anchor does not suppress same numeric series candidate`() {
        val anchors = ContinueWatchingCanonicalization.watchedAnchorsFromProgress(
            listOf(
                watchProgress(
                    contentId = "tmdb:movie:550",
                    season = null,
                    episode = null,
                    lastWatched = 50_000L
                )
            )
        )

        assertFalse(
            ContinueWatchingCanonicalization.isSuppressedByWatchedAnchors(
                lookupKeys = ContinueWatchingCanonicalization.lookupKeysForRawContentId("tmdb:tv:550"),
                season = 1,
                episode = 1,
                updatedAtMs = 40_000L,
                anchors = anchors
            )
        )
    }

    private fun nextUpEntry(
        firstAired: String? = null,
        firstAiredMs: Long = 0L,
        tvdbAvailabilityInstantMs: Long? = null
    ): TrackingNextUpEntry =
        TrackingNextUpEntry(
            contentId = "tvdb:430780",
            contentType = "series",
            name = "Test Series",
            season = 1,
            episode = 8,
            episodeTitle = "Episode 8",
            videoId = "tvdb:430780:1:8",
            firstAired = firstAired,
            firstAiredMs = firstAiredMs,
            activityAtMs = 1_000L,
            tvdbAvailabilityInstantMs = tvdbAvailabilityInstantMs
        )

    private fun watchProgress(
        contentId: String,
        season: Int?,
        episode: Int?,
        lastWatched: Long
    ): WatchProgress =
        WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Test Series",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:s${season}e$episode",
            season = season,
            episode = episode,
            episodeTitle = "Episode",
            position = 900L,
            duration = 1_000L,
            lastWatched = lastWatched
        )
}
