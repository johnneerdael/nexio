package com.nexio.tv.data.local

import com.nexio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchedItemsPreferencesTest {

    @Test
    fun `removeWatchedItems clears all episode rows for show-level alias clear`() {
        val items = listOf(
            watchedItem(contentId = "tmdb:101", season = 1, episode = 1, watchedAt = 1_000L),
            watchedItem(contentId = "tt1234567", season = 1, episode = 2, watchedAt = 2_000L),
            watchedItem(contentId = "tt7654321", season = 1, episode = 1, watchedAt = 3_000L)
        )

        val remaining = removeWatchedItems(
            items = items,
            contentIds = setOf("tmdb:101", "tt1234567"),
            season = null,
            episode = null
        )

        assertEquals(listOf(items.last()), remaining)
    }

    @Test
    fun `removeWatchedItems clears a specific episode across alias forms`() {
        val items = listOf(
            watchedItem(contentId = "tmdb:101", season = 1, episode = 1, watchedAt = 1_000L),
            watchedItem(contentId = "tt1234567", season = 1, episode = 2, watchedAt = 2_000L),
            watchedItem(contentId = "tt7654321", season = 1, episode = 1, watchedAt = 3_000L)
        )

        val remaining = removeWatchedItems(
            items = items,
            contentIds = setOf("tmdb:101", "tt1234567"),
            season = 1,
            episode = 2
        )

        assertEquals(listOf(items.first(), items.last()), remaining)
    }

    @Test
    fun `upsertWatchedItem replaces alias-matched duplicate episode rows`() {
        val items = listOf(
            watchedItem(contentId = "tmdb:101", season = 1, episode = 1, watchedAt = 1_000L),
            watchedItem(contentId = "tt7654321", season = 1, episode = 1, watchedAt = 2_000L)
        )
        val replacement = watchedItem(
            contentId = "tt1234567",
            season = 1,
            episode = 1,
            watchedAt = 4_000L
        )

        val updated = upsertWatchedItem(
            items = items,
            item = replacement,
            contentIds = setOf("tmdb:101", "tt1234567")
        )

        assertEquals(listOf(items[1], replacement), updated)
    }

    private fun watchedItem(
        contentId: String,
        season: Int?,
        episode: Int?,
        watchedAt: Long
    ) = WatchedItem(
        contentId = contentId,
        contentType = "series",
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = watchedAt
    )
}
