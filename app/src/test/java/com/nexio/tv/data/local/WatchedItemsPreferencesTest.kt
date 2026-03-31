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
    fun `guest watched items migrate into session identity bucket`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                sessionIdentity = "session-123",
                username = null,
                userSlug = null
            )
        )
        val storeFile = File.createTempFile("watched-items-session", ".preferences_pb")
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
            store[stringSetPreferencesKey("watched_items_guest")] = setOf(Gson().toJson(item))
        }

        assertEquals(listOf(item), preferences.getAllItems())

        val stored = dataStore.data.first()
        assertEquals(
            setOf(Gson().toJson(item)),
            stored[stringSetPreferencesKey("watched_items_session-123")]
        )
        assertEquals(null, stored[stringSetPreferencesKey("watched_items_guest")])
    }

    @Test
    fun `session identity watched items migrate when user metadata arrives`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                sessionIdentity = "session-123",
                username = null,
                userSlug = null
            )
        )
        val storeFile = File.createTempFile("watched-items-session-to-user", ".preferences_pb")
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
        assertEquals(
            setOf(Gson().toJson(item)),
            dataStore.data.first()[stringSetPreferencesKey("watched_items_session-123")]
        )

        authState.value = authState.value.copy(username = "alice", userSlug = "alice")
        advanceUntilIdle()

        assertEquals(listOf(item), preferences.getAllItems())

        val stored = dataStore.data.first()
        assertEquals(setOf(Gson().toJson(item)), stored[stringSetPreferencesKey("watched_items_alice")])
        assertEquals(null, stored[stringSetPreferencesKey("watched_items_session-123")])
    }

    @Test
    fun `read flows observe only the active session bucket after migration`() = runTest {
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                sessionIdentity = "session-123"
            )
        )
        val storeFile = File.createTempFile("watched-items-flow", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authState
        )
        val item = watchedItem(
            contentId = "tmdb:101",
            season = 1,
            episode = 2,
            watchedAt = 1_000L
        )

        dataStore.edit { store ->
            store[stringSetPreferencesKey("watched_items_guest")] = setOf(Gson().toJson(item))
        }

        assertEquals(setOf(1 to 2), preferences.getWatchedEpisodesForContent("tmdb:101").first())

        val stored = dataStore.data.first()
        assertEquals(
            setOf(Gson().toJson(item)),
            stored[stringSetPreferencesKey("watched_items_session-123")]
        )
        assertEquals(null, stored[stringSetPreferencesKey("watched_items_guest")])
    }

    @Test
    fun `legacy auth hash bucket migrates after session identity backfill`() = runTest {
        val authStoreFile = File.createTempFile("trakt-auth-upgrade", ".preferences_pb")
        authStoreFile.deleteOnExit()
        val authStoreBacking = PreferenceDataStoreFactory.create(scope = backgroundScope) { authStoreFile }
        authStoreBacking.edit { store ->
            store[androidx.datastore.preferences.core.stringPreferencesKey("access_token")] = "access"
            store[androidx.datastore.preferences.core.stringPreferencesKey("refresh_token")] = "refresh"
        }
        val authDataStore = TraktAuthDataStore(dataStore = authStoreBacking)
        val storeFile = File.createTempFile("watched-items-auth-hash", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authDataStore.state,
            ensureSessionIdentityBackfilled = authDataStore::ensureSessionIdentityBackfilled
        )
        val item = watchedItem(
            contentId = "tmdb:101",
            season = 1,
            episode = 1,
            watchedAt = 1_000L
        )
        val legacyState = TraktAuthState(accessToken = "access", refreshToken = "refresh")
        val legacyKey = "watched_items_${legacyAuthHashSessionKeyForState(legacyState)}"

        dataStore.edit { store ->
            store[stringSetPreferencesKey(legacyKey)] = setOf(Gson().toJson(item))
        }

        assertEquals(listOf(item), preferences.getAllItems())

        val sessionIdentity = authDataStore.state.first().sessionIdentity!!
        val stored = dataStore.data.first()
        assertEquals(
            setOf(Gson().toJson(item)),
            stored[stringSetPreferencesKey("watched_items_${sessionIdentity.lowercase()}")]
        )
        assertEquals(null, stored[stringSetPreferencesKey(legacyKey)])
    }

    @Test
    fun `unrelated legacy auth hash buckets do not merge into the active session`() = runTest {
        val authStoreFile = File.createTempFile("trakt-auth-multi-upgrade", ".preferences_pb")
        authStoreFile.deleteOnExit()
        val authStoreBacking = PreferenceDataStoreFactory.create(scope = backgroundScope) { authStoreFile }
        authStoreBacking.edit { store ->
            store[androidx.datastore.preferences.core.stringPreferencesKey("access_token")] = "access"
            store[androidx.datastore.preferences.core.stringPreferencesKey("refresh_token")] = "refresh"
        }
        val authDataStore = TraktAuthDataStore(dataStore = authStoreBacking)
        val storeFile = File.createTempFile("watched-items-auth-hash-multi", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        val preferences = WatchedItemsPreferences(
            dataStore = dataStore,
            authState = authDataStore.state,
            ensureSessionIdentityBackfilled = authDataStore::ensureSessionIdentityBackfilled
        )
        val firstItem = watchedItem(contentId = "tmdb:101", season = 1, episode = 1, watchedAt = 1_000L)
        val secondItem = watchedItem(contentId = "tt1234567", season = 1, episode = 2, watchedAt = 2_000L)
        val currentLegacyKey = "watched_items_${legacyAuthHashSessionKeyForState(TraktAuthState(accessToken = "access", refreshToken = "refresh"))}"
        val olderLegacyKey = "watched_items_${legacyAuthHashSessionKeyForState(TraktAuthState(accessToken = "older-access", refreshToken = "older-refresh"))}"

        dataStore.edit { store ->
            store[stringSetPreferencesKey(currentLegacyKey)] = setOf(Gson().toJson(firstItem))
            store[stringSetPreferencesKey(olderLegacyKey)] = setOf(Gson().toJson(secondItem))
        }

        assertEquals(listOf(firstItem), preferences.getAllItems())

        val sessionIdentity = authDataStore.state.first().sessionIdentity!!
        val stored = dataStore.data.first()
        assertEquals(
            setOf(Gson().toJson(firstItem)),
            stored[stringSetPreferencesKey("watched_items_${sessionIdentity.lowercase()}")]
        )
        assertEquals(null, stored[stringSetPreferencesKey(currentLegacyKey)])
        assertEquals(
            setOf(Gson().toJson(secondItem)),
            stored[stringSetPreferencesKey(olderLegacyKey)]
        )
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
