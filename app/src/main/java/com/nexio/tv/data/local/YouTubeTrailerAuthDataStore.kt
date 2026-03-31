package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.youTubeTrailerAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_trailer_auth"
)

data class YouTubeTrailerAuthSettings(
    val isSignedIn: Boolean = false,
    val lastCookieRefreshEpochMs: Long? = null,
    val sessionVersion: Int = 0,
    val sessionStatusMessage: String = "Signed out"
)

@Singleton
class YouTubeTrailerAuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.youTubeTrailerAuthDataStore

    private val isSignedInKey = booleanPreferencesKey("youtube_trailer_is_signed_in")
    private val lastCookieRefreshEpochMsKey =
        longPreferencesKey("youtube_trailer_last_cookie_refresh_epoch_ms")
    private val cookieFileVersionKey = intPreferencesKey("youtube_trailer_cookie_file_version")
    private val sessionStatusMessageKey = stringPreferencesKey("youtube_trailer_session_status_message")

    val settings: Flow<YouTubeTrailerAuthSettings> = dataStore.data.map { preferences ->
        YouTubeTrailerAuthSettings(
            isSignedIn = preferences[isSignedInKey] ?: false,
            lastCookieRefreshEpochMs = preferences[lastCookieRefreshEpochMsKey],
            sessionVersion = preferences[cookieFileVersionKey] ?: 0,
            sessionStatusMessage = preferences[sessionStatusMessageKey] ?: "Signed out"
        )
    }

    suspend fun markSignedIn(
        sessionVersion: Int = 1,
        lastCookieRefreshEpochMs: Long = System.currentTimeMillis(),
        sessionStatusMessage: String = "Signed in"
    ) {
        dataStore.edit { preferences ->
            preferences[isSignedInKey] = true
            preferences[cookieFileVersionKey] = sessionVersion
            preferences[lastCookieRefreshEpochMsKey] = lastCookieRefreshEpochMs
            preferences[sessionStatusMessageKey] = sessionStatusMessage
        }
    }

    suspend fun updateSessionStatusMessage(message: String) {
        dataStore.edit { preferences ->
            preferences[sessionStatusMessageKey] = message
        }
    }

    suspend fun updateCookieRefresh(
        refreshedAtEpochMs: Long = System.currentTimeMillis(),
        sessionVersion: Int = 1,
        sessionStatusMessage: String = "Session refreshed"
    ) {
        dataStore.edit { preferences ->
            preferences[lastCookieRefreshEpochMsKey] = refreshedAtEpochMs
            preferences[cookieFileVersionKey] = sessionVersion
            preferences[sessionStatusMessageKey] = sessionStatusMessage
        }
    }

    suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences[isSignedInKey] = false
            preferences.remove(lastCookieRefreshEpochMsKey)
            preferences[cookieFileVersionKey] = 0
            preferences[sessionStatusMessageKey] = "Signed out"
        }
    }
}
