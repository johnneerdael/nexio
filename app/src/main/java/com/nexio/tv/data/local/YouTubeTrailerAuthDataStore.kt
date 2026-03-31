package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.data.trailer.helper.YouTubeDeviceCodeSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.youTubeTrailerAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_trailer_auth"
)

private const val DEFAULT_SIGNED_OUT_MESSAGE = "Signed out"

data class YouTubeTrailerAuthSettings(
    val isSignedIn: Boolean = false,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val verificationUrlComplete: String? = null,
    val deviceCodeExpiresAtEpochMs: Long? = null,
    val pollIntervalSeconds: Int = 5,
    val statusMessage: String = DEFAULT_SIGNED_OUT_MESSAGE
) {
    val sessionStatusMessage: String
        get() = statusMessage
}

@Singleton
class YouTubeTrailerAuthDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.youTubeTrailerAuthDataStore)

    private val isSignedInKey = booleanPreferencesKey("youtube_trailer_is_signed_in")
    private val deviceCodeKey = stringPreferencesKey("youtube_trailer_device_code")
    private val userCodeKey = stringPreferencesKey("youtube_trailer_user_code")
    private val verificationUrlKey = stringPreferencesKey("youtube_trailer_verification_url")
    private val verificationUrlCompleteKey =
        stringPreferencesKey("youtube_trailer_verification_url_complete")
    private val deviceCodeExpiresAtEpochMsKey =
        longPreferencesKey("youtube_trailer_device_code_expires_at_epoch_ms")
    private val pollIntervalSecondsKey = intPreferencesKey("youtube_trailer_poll_interval_seconds")
    private val sessionStatusMessageKey =
        stringPreferencesKey("youtube_trailer_session_status_message")

    val settings: Flow<YouTubeTrailerAuthSettings> = dataStore.data.map { preferences ->
        YouTubeTrailerAuthSettings(
            isSignedIn = preferences[isSignedInKey] ?: false,
            deviceCode = preferences[deviceCodeKey],
            userCode = preferences[userCodeKey],
            verificationUrl = preferences[verificationUrlKey],
            verificationUrlComplete = preferences[verificationUrlCompleteKey],
            deviceCodeExpiresAtEpochMs = preferences[deviceCodeExpiresAtEpochMsKey],
            pollIntervalSeconds = preferences[pollIntervalSecondsKey] ?: 5,
            statusMessage = preferences[sessionStatusMessageKey] ?: DEFAULT_SIGNED_OUT_MESSAGE
        )
    }

    suspend fun saveDeviceFlow(
        session: YouTubeDeviceCodeSession
    ) {
        dataStore.edit { preferences ->
            preferences[isSignedInKey] = false
            preferences[deviceCodeKey] = session.deviceCode
            preferences[userCodeKey] = session.userCode
            preferences[verificationUrlKey] = session.verificationUrl
            session.verificationUrlComplete?.let { complete ->
                preferences[verificationUrlCompleteKey] = complete
            } ?: preferences.remove(verificationUrlCompleteKey)
            preferences[deviceCodeExpiresAtEpochMsKey] =
                System.currentTimeMillis() + (session.expiresInSeconds * 1000L)
            preferences[pollIntervalSecondsKey] = session.intervalSeconds
            preferences[sessionStatusMessageKey] = "Approve the code on another device"
        }
    }

    suspend fun markSignedIn(
        sessionStatusMessage: String = "Signed in"
    ) {
        dataStore.edit { preferences ->
            preferences[isSignedInKey] = true
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(verificationUrlCompleteKey)
            preferences.remove(deviceCodeExpiresAtEpochMsKey)
            preferences.remove(pollIntervalSecondsKey)
            preferences[sessionStatusMessageKey] = sessionStatusMessage
        }
    }

    suspend fun updateSessionStatusMessage(message: String) {
        dataStore.edit { preferences ->
            preferences[sessionStatusMessageKey] = message
        }
    }

    suspend fun updatePollInterval(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[pollIntervalSecondsKey] = seconds
        }
    }

    suspend fun clearDeviceFlow() {
        dataStore.edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(verificationUrlCompleteKey)
            preferences.remove(deviceCodeExpiresAtEpochMsKey)
            preferences.remove(pollIntervalSecondsKey)
        }
    }

    suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences[isSignedInKey] = false
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(verificationUrlCompleteKey)
            preferences.remove(deviceCodeExpiresAtEpochMsKey)
            preferences.remove(pollIntervalSecondsKey)
            preferences[sessionStatusMessageKey] = DEFAULT_SIGNED_OUT_MESSAGE
        }
    }
}
