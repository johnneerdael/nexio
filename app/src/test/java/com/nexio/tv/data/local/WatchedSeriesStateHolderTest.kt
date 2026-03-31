package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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
}
