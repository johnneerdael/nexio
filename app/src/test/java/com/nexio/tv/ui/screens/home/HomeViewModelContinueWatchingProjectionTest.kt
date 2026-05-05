package com.nexio.tv.ui.screens.home

import com.nexio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies that [dedupContinueWatchingByProjectedIdentity] collapses two ContinueWatching entries
 * that map to the same projected [EpisodeCoordinate.identityKey] (e.g. kitsu:13881 S3E1 and a
 * TVDB S3E1 entry whose projection resolves to the same tvdb:…:s3e1 key) into a single row.
 *
 * The dedup function is a pure, standalone utility — no ViewModel or coroutine harness needed.
 */
class HomeViewModelContinueWatchingProjectionTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun inProgressItem(
        contentId: String,
        season: Int,
        episode: Int,
        lastWatched: Long = 1_000L
    ) = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = contentId,
            contentType = "series",
            name = "Test Anime",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$contentId:$season:$episode",
            season = season,
            episode = episode,
            episodeTitle = "Episode $episode",
            position = 100L,
            duration = 1_000L,
            lastWatched = lastWatched
        )
    )

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `two entries with identical projected identity key collapse to one row`() {
        val kitsuItem = inProgressItem("kitsu:13881", season = 3, episode = 1, lastWatched = 2_000L)
        val tvdbItem = inProgressItem("tvdb:279121", season = 3, episode = 1, lastWatched = 1_000L)

        // Both items project to the same identity key (as resolved by AnimeSeasonProjectionResolver).
        val sharedKey = "tvdb:279121:s3e1"
        val identityKeyFor: (ContinueWatchingItem) -> String = { item ->
            when (item.contentId()) {
                "kitsu:13881" -> sharedKey  // kitsu item is projected to the TVDB coordinate
                "tvdb:279121" -> sharedKey  // native TVDB item already has this key
                else -> item.contentId()
            }
        }

        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(kitsuItem, tvdbItem),
            identityKeyFor = identityKeyFor
        )

        assertEquals("Two items sharing a projected identity key must collapse to one", 1, result.size)
    }

    @Test
    fun `first seen entry wins when two entries share a projected identity key`() {
        val kitsuItem = inProgressItem("kitsu:13881", season = 3, episode = 1)
        val tvdbItem = inProgressItem("tvdb:279121", season = 3, episode = 1)

        val sharedKey = "tvdb:279121:s3e1"
        val identityKeyFor: (ContinueWatchingItem) -> String = { item ->
            if (item.contentId() in listOf("kitsu:13881", "tvdb:279121")) sharedKey
            else item.contentId()
        }

        // kitsu item is listed first — it should survive dedup
        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(kitsuItem, tvdbItem),
            identityKeyFor = identityKeyFor
        )

        assertEquals(1, result.size)
        assertEquals("kitsu:13881", result[0].contentId())
    }

    @Test
    fun `entries with distinct projected identity keys are not collapsed`() {
        val item1 = inProgressItem("kitsu:13881", season = 3, episode = 1)
        val item2 = inProgressItem("kitsu:99999", season = 1, episode = 1)

        val identityKeyFor: (ContinueWatchingItem) -> String = { item ->
            when (item.contentId()) {
                "kitsu:13881" -> "tvdb:279121:s3e1"
                "kitsu:99999" -> "tvdb:888888:s1e1"
                else -> item.contentId()
            }
        }

        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(item1, item2),
            identityKeyFor = identityKeyFor
        )

        assertEquals("Items with distinct projected keys must each appear", 2, result.size)
    }

    @Test
    fun `empty list returns empty list`() {
        val result = dedupContinueWatchingByProjectedIdentity(
            items = emptyList(),
            identityKeyFor = { it.contentId() }
        )
        assertEquals(0, result.size)
    }

    @Test
    fun `single item list returns same item unchanged`() {
        val item = inProgressItem("kitsu:13881", season = 2, episode = 5)
        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(item),
            identityKeyFor = { it.contentId() }
        )
        assertEquals(1, result.size)
        assertEquals("kitsu:13881", result[0].contentId())
    }

    @Test
    fun `non-kitsu items with distinct content ids are not collapsed`() {
        val ttItem = inProgressItem("tt1234567", season = 1, episode = 1)
        val tvdbItem = inProgressItem("tvdb:279121", season = 1, episode = 1)

        // Each item uses its own content-id as identity key (no projection applied)
        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(ttItem, tvdbItem),
            identityKeyFor = { it.contentId() }
        )

        assertEquals(2, result.size)
    }

    @Test
    fun `preserves original order of surviving entries`() {
        val a = inProgressItem("kitsu:1", season = 1, episode = 1)
        val b = inProgressItem("kitsu:2", season = 1, episode = 2)
        val c = inProgressItem("kitsu:3", season = 1, episode = 3)
        // 'c' projects to the same key as 'a' — 'a' wins (first seen), 'b' and 'a' survive
        val identityKeyFor: (ContinueWatchingItem) -> String = { item ->
            when (item.contentId()) {
                "kitsu:1", "kitsu:3" -> "shared-key"
                else -> item.contentId()
            }
        }

        val result = dedupContinueWatchingByProjectedIdentity(
            items = listOf(a, b, c),
            identityKeyFor = identityKeyFor
        )

        assertEquals(2, result.size)
        assertEquals("kitsu:1", result[0].contentId())
        assertEquals("kitsu:2", result[1].contentId())
    }
}
