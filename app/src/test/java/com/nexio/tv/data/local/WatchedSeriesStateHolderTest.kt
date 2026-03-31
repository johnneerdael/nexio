package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedSeriesStateHolderTest {

    @Test
    fun `setSeriesWatched persists aliases per trakt session`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = "alice",
                userSlug = "alice"
            )
        )
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val authStore = mockk<TraktAuthDataStore> {
            every { state } returns authState
        }
        coEvery { authStore.ensureSessionIdentityBackfilled() } returns Unit

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        holder.setSeriesWatched(ids = listOf("tmdb:101", "tt1234567"), watched = true)

        val reloaded = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        reloaded.loadFromDisk()

        assertTrue(reloaded.isSeriesWatched("tmdb:101"))
        assertTrue(reloaded.isSeriesWatched("tt1234567"))

        authState.value = TraktAuthState(
            accessToken = "access",
            refreshToken = "refresh",
            username = "bob",
            userSlug = "bob"
        )
        reloaded.loadFromDisk()

        assertFalse(reloaded.isSeriesWatched("tmdb:101"))
        assertFalse(reloaded.isSeriesWatched("tt1234567"))
    }

    @Test
    fun `setSeriesWatched remembers alias groups even when series is not fully watched`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = "alice",
                userSlug = "alice"
            )
        )
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val authStore = mockk<TraktAuthDataStore> {
            every { state } returns authState
        }
        coEvery { authStore.ensureSessionIdentityBackfilled() } returns Unit

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        holder.setSeriesWatched(ids = listOf("tmdb:101", "tt1234567"), watched = false)

        val reloaded = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        reloaded.loadFromDisk()

        assertFalse(reloaded.isSeriesWatched("tmdb:101"))
        assertEquals(
            setOf("tmdb:101", "tt1234567"),
            reloaded.matchingEntryIds("tmdb:101")
        )
        assertEquals(
            setOf("tmdb:101", "tt1234567"),
            reloaded.matchingEntryIds("tt1234567")
        )
    }

    @Test
    fun `session switch clears in-memory entries without process restart`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                username = "alice",
                userSlug = "alice"
            )
        )
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val authStore = mockk<TraktAuthDataStore> {
            every { state } returns authState
        }
        coEvery { authStore.ensureSessionIdentityBackfilled() } returns Unit

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        holder.setSeriesWatched(ids = listOf("tmdb:101", "tt1234567"), watched = true)

        assertTrue(holder.isSeriesWatched("tmdb:101"))

        authState.value = TraktAuthState(
            accessToken = "access",
            refreshToken = "refresh",
            username = "bob",
            userSlug = "bob"
        )
        advanceUntilIdle()

        assertFalse(holder.isSeriesWatched("tmdb:101"))
        assertEquals(setOf("tmdb:101"), holder.matchingEntryIds("tmdb:101"))
    }

    @Test
    fun `guest holder bucket migrates into session identity`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                sessionIdentity = "session-123",
                username = null,
                userSlug = null
            )
        )
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val authStore = mockk<TraktAuthDataStore> {
            every { state } returns authState
        }
        coEvery { authStore.ensureSessionIdentityBackfilled() } returns Unit

        prefs.edit()
            .putString(
                "entries_guest",
                "[{\"ids\":[\"tmdb:101\",\"tt1234567\"]}]"
            )
            .apply()
        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)

        advanceUntilIdle()

        assertEquals("session-123", holder.activeSessionKey.value)
        assertTrue(holder.isSeriesWatched("tmdb:101"))
    }

    @Test
    fun `session identity holder bucket migrates when user metadata arrives`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authState = MutableStateFlow(
            TraktAuthState(
                accessToken = "access",
                refreshToken = "refresh",
                sessionIdentity = "session-123",
                username = null,
                userSlug = null
            )
        )
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val authStore = mockk<TraktAuthDataStore> {
            every { state } returns authState
        }
        coEvery { authStore.ensureSessionIdentityBackfilled() } returns Unit

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        holder.setSeriesWatched(ids = listOf("tmdb:101", "tt1234567"), watched = true)

        assertEquals("session-123", holder.activeSessionKey.value)
        assertTrue(holder.isSeriesWatched("tmdb:101"))

        authState.value = authState.value.copy(username = "alice", userSlug = "alice")
        advanceUntilIdle()

        assertEquals("alice", holder.activeSessionKey.value)
        assertTrue(holder.isSeriesWatched("tmdb:101"))
        assertEquals(
            setOf("tmdb:101", "tt1234567"),
            holder.matchingEntryIds("tmdb:101")
        )
    }

    @Test
    fun `legacy auth hash holder bucket migrates after session identity backfill`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authStoreFile = java.io.File.createTempFile("trakt-auth-holder-upgrade", ".preferences_pb")
        authStoreFile.deleteOnExit()
        val authBacking = PreferenceDataStoreFactory.create(scope = backgroundScope) { authStoreFile }
        authBacking.edit { store ->
            store[stringPreferencesKey("access_token")] = "access"
            store[stringPreferencesKey("refresh_token")] = "refresh"
        }
        val authStore = TraktAuthDataStore(dataStore = authBacking)
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val legacyState = TraktAuthState(accessToken = "access", refreshToken = "refresh")
        val legacyKey = legacyAuthHashSessionKeyForState(legacyState)!!
        prefs.edit()
            .putString(
                "entries_${legacyKey.lowercase()}",
                "[{\"ids\":[\"tmdb:101\",\"tt1234567\"]}]"
            )
            .apply()

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        advanceUntilIdle()

        val sessionIdentity = authStore.state.first().sessionIdentity!!
        assertEquals(sessionIdentity, holder.activeSessionKey.value)
        assertTrue(holder.isSeriesWatched("tmdb:101"))
        assertEquals(
            setOf("tmdb:101", "tt1234567"),
            holder.matchingEntryIds("tt1234567")
        )
    }

    @Test
    fun `all legacy auth hash holder buckets migrate after session identity backfill`() = runTest {
        val prefs = InMemorySharedPreferences()
        val authStoreFile = java.io.File.createTempFile("trakt-auth-holder-multi-upgrade", ".preferences_pb")
        authStoreFile.deleteOnExit()
        val authBacking = PreferenceDataStoreFactory.create(scope = backgroundScope) { authStoreFile }
        authBacking.edit { store ->
            store[stringPreferencesKey("access_token")] = "access"
            store[stringPreferencesKey("refresh_token")] = "refresh"
        }
        val authStore = TraktAuthDataStore(dataStore = authBacking)
        val context = mockk<Context> {
            every {
                getSharedPreferences("watched_series_state", Context.MODE_PRIVATE)
            } returns prefs
        }
        val currentLegacyKey = legacyAuthHashSessionKeyForState(
            TraktAuthState(accessToken = "access", refreshToken = "refresh")
        )!!
        val olderLegacyKey = legacyAuthHashSessionKeyForState(
            TraktAuthState(accessToken = "older-access", refreshToken = "older-refresh")
        )!!
        prefs.edit()
            .putString(
                "entries_${currentLegacyKey.lowercase()}",
                "[{\"ids\":[\"tmdb:101\",\"tt1234567\"]}]"
            )
            .putString(
                "entries_${olderLegacyKey.lowercase()}",
                "[{\"ids\":[\"tmdb:202\",\"tt7654321\"]}]"
            )
            .apply()

        val holder = WatchedSeriesStateHolder(context, authStore, backgroundScope)
        advanceUntilIdle()

        val sessionIdentity = authStore.state.first().sessionIdentity!!
        assertEquals(sessionIdentity, holder.activeSessionKey.value)
        assertTrue(holder.isSeriesWatched("tmdb:101"))
        assertTrue(holder.isSeriesWatched("tmdb:202"))
        assertEquals(
            setOf("tmdb:202", "tt7654321"),
            holder.matchingEntryIds("tt7654321")
        )
    }
}
