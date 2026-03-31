package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class TraktAuthDataStoreTest {

    @Test
    fun `ensureSessionIdentityBackfilled creates stable identity for already authenticated installs`() = runTest {
        val storeFile = File.createTempFile("trakt-auth-backfill", ".preferences_pb")
        storeFile.deleteOnExit()
        val rawDataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        rawDataStore.edit { preferences ->
            preferences[stringPreferencesKey("access_token")] = "access-1"
            preferences[stringPreferencesKey("refresh_token")] = "refresh-1"
            preferences[stringPreferencesKey("token_type")] = "bearer"
        }
        val dataStore = TraktAuthDataStore(dataStore = rawDataStore)

        dataStore.ensureSessionIdentityBackfilled()
        val firstIdentity = dataStore.state.first().sessionIdentity
        dataStore.ensureSessionIdentityBackfilled()
        val secondIdentity = dataStore.state.first().sessionIdentity

        assertNotNull(firstIdentity)
        assertEquals(firstIdentity, secondIdentity)
    }

    @Test
    fun `saveToken creates stable session identity that survives token refresh`() = runTest {
        val storeFile = File.createTempFile("trakt-auth", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = TraktAuthDataStore(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        )

        dataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                tokenType = "bearer",
                expiresIn = 3600,
                createdAt = 1_000L
            )
        )
        val firstIdentity = dataStore.state.first().sessionIdentity

        dataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access-2",
                refreshToken = "refresh-2",
                tokenType = "bearer",
                expiresIn = 3600,
                createdAt = 2_000L
            )
        )
        val refreshedState = dataStore.state.first()

        assertNotNull(firstIdentity)
        assertEquals(firstIdentity, refreshedState.sessionIdentity)
        assertEquals("access-2", refreshedState.accessToken)
        assertEquals("refresh-2", refreshedState.refreshToken)
    }

    @Test
    fun `clearAuth removes stable session identity`() = runTest {
        val storeFile = File.createTempFile("trakt-auth-clear", ".preferences_pb")
        storeFile.deleteOnExit()
        val dataStore = TraktAuthDataStore(
            dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) { storeFile }
        )

        dataStore.saveToken(
            TraktTokenResponseDto(
                accessToken = "access-1",
                refreshToken = "refresh-1",
                tokenType = "bearer",
                expiresIn = 3600,
                createdAt = 1_000L
            )
        )
        assertNotNull(dataStore.state.first().sessionIdentity)

        dataStore.clearAuth()

        val clearedState = dataStore.state.first()
        assertNull(clearedState.sessionIdentity)
        assertFalse(clearedState.isAuthenticated)
    }
}
