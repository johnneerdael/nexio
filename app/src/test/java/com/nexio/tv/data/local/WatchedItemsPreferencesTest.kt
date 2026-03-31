package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.domain.model.WatchedItem
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File

class WatchedItemsPreferencesTest {

    @Test
    fun `watched items are isolated per trakt session`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = "alice",
                userSlug = "alice"
            )
        )
        val storeFile = File.createTempFile("watched-items", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authState
        )
        val aliceItem = watchedItem(
            contentId = "tmdb:101",
            season = 1,
            episode = 1,
            watchedAt = 1_000L
        )
        val bobItem = watchedItem(
            contentId = "tmdb:202",
            season = 2,
            episode = 3,
            watchedAt = 2_000L
        )

        preferences.markAsWatched(aliceItem)
        assertEquals(listOf(aliceItem), preferences.getAllItems())

        authState.value = TraktAuthState(
            accessToken = "access",
            refreshToken = "refresh",
            username = "bob",
            userSlug = "bob"
        )
        advanceUntilIdle()

        assertEquals(emptyList<WatchedItem>(), preferences.getAllItems())

        preferences.markAsWatched(bobItem)
        assertEquals(listOf(bobItem), preferences.getAllItems())

        authState.value = TraktAuthState(
            accessToken = "access",
            refreshToken = "refresh",
            username = "alice",
            userSlug = "alice"
        )
        advanceUntilIdle()

        assertEquals(listOf(aliceItem), preferences.getAllItems())
    }

    @Test
    fun `legacy watched items key migrates into the active session bucket`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = "alice",
                userSlug = "alice"
            )
        )
        val storeFile = File.createTempFile("watched-items-legacy", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authState
        )
        val item = watchedItem(
            contentId = "tmdb:101",
            season = 1,
            episode = 1,
            watchedAt = 1_000L
        )

        dataStore.edit { store ->
            store[stringSetPreferencesKey("watched_items")] = setOf(Gson().toJson(item))
        }

        assertEquals(listOf(item), preferences.getAllItems())

        val stored = dataStore.data.first()
        assertEquals(
            setOf(Gson().toJson(item)),
            stored[stringSetPreferencesKey("watched_items_alice")]
        )
        assertEquals(null, stored[stringSetPreferencesKey("watched_items")])
    }

    @Test
    fun `token derived watched items bucket migrates when user metadata arrives`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = null,
                userSlug = null
            )
        )
        val storeFile = File.createTempFile("watched-items-token", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authState
        )
        val item = watchedItem(
            contentId = "tmdb:101",
            season = 1,
            episode = 1,
            watchedAt = 1_000L
        )

        preferences.markAsWatched(item)
        val tokenKey = "watched_items_" + traktSessionKeyForState(authState.value)
        assertEquals(setOf(Gson().toJson(item)), dataStore.data.first()[stringSetPreferencesKey(tokenKey)])

        authState.value = authState.value.copy(username = "alice", userSlug = "alice")
        advanceUntilIdle()

        assertEquals(listOf(item), preferences.getAllItems())

        val stored = dataStore.data.first()
        assertEquals(setOf(Gson().toJson(item)), stored[stringSetPreferencesKey("watched_items_alice")])
        assertEquals(null, stored[stringSetPreferencesKey(tokenKey)])
    }

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
