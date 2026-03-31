package com.nexio.tv.data.trailer.helper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeTrailerTokenStoreTest {

    @Test
    fun `state starts empty`() = runTest {
        val store = YouTubeTrailerTokenStore(dataStore = createDataStore(backgroundScope))

        val state = store.state.first()

        assertFalse(state.isAuthenticated)
        assertNull(state.accessToken)
        assertNull(state.refreshToken)
    }

    @Test
    fun `saveSession persists tokens and delegated session metadata`() = runTest {
        val store = YouTubeTrailerTokenStore(dataStore = createDataStore(backgroundScope))

        store.saveSession(
            YouTubeTrailerTokenSession(
                accessToken = "access-token",
                refreshToken = "refresh-token",
                tokenType = "Bearer",
                expiresInSeconds = 3_600,
                delegatedPageId = "page-42",
                authUser = "0"
            )
        )

        val state = store.state.first()

        assertEquals("access-token", state.accessToken)
        assertEquals("refresh-token", state.refreshToken)
        assertEquals("Bearer", state.tokenType)
        assertEquals(3_600, state.expiresInSeconds)
        assertEquals("page-42", state.delegatedPageId)
        assertEquals("0", state.authUser)
    }

    @Test
    fun `clearSession removes persisted tokens`() = runTest {
        val store = YouTubeTrailerTokenStore(dataStore = createDataStore(backgroundScope))
        store.saveSession(
            YouTubeTrailerTokenSession(
                accessToken = "access-token",
                refreshToken = "refresh-token"
            )
        )

        store.clearSession()

        val state = store.state.first()

        assertFalse(state.isAuthenticated)
        assertNull(state.accessToken)
        assertNull(state.refreshToken)
    }

    private fun createDataStore(
        scope: CoroutineScope
    ): DataStore<androidx.datastore.preferences.core.Preferences> {
        val tempFile = File.createTempFile("youtube_trailer_token_store", ".preferences_pb")
        tempFile.deleteOnExit()
        return PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFile }
        )
    }
}
