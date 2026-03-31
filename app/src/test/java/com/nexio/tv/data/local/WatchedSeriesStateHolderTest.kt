package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
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

        val holder = WatchedSeriesStateHolder(context, authStore)
        holder.setSeriesWatched(ids = listOf("tmdb:101", "tt1234567"), watched = true)

        val reloaded = WatchedSeriesStateHolder(context, authStore)
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
}
