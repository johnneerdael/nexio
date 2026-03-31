package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.traktAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trakt_auth_store"
)

data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val sessionIdentity: String? = null,
    val username: String? = null,
    val userSlug: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null,
    val pollInterval: Int? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
class TraktAuthDataStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(context.traktAuthDataStore)

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")
    private val sessionIdentityKey = stringPreferencesKey("session_identity")

    private val usernameKey = stringPreferencesKey("username")
    private val userSlugKey = stringPreferencesKey("user_slug")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    val state: Flow<TraktAuthState> = dataStore.data.map { preferences ->
        TraktAuthState(
            accessToken = preferences[accessTokenKey],
            refreshToken = preferences[refreshTokenKey],
            tokenType = preferences[tokenTypeKey],
            createdAt = preferences[createdAtKey],
            expiresIn = preferences[expiresInKey],
            sessionIdentity = preferences[sessionIdentityKey],
            username = preferences[usernameKey],
            userSlug = preferences[userSlugKey],
            deviceCode = preferences[deviceCodeKey],
            userCode = preferences[userCodeKey],
            verificationUrl = preferences[verificationUrlKey],
            expiresAt = preferences[expiresAtKey],
            pollInterval = preferences[pollIntervalKey]
        )
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    suspend fun saveToken(token: TraktTokenResponseDto) {
        dataStore.edit { preferences ->
            preferences[accessTokenKey] = token.accessToken
            preferences[refreshTokenKey] = token.refreshToken
            preferences[tokenTypeKey] = token.tokenType
            preferences[createdAtKey] = token.createdAt
            preferences[expiresInKey] = token.expiresIn
            if (preferences[sessionIdentityKey].isNullOrBlank()) {
                preferences[sessionIdentityKey] = UUID.randomUUID().toString()
            }
        }
    }

    suspend fun saveUser(username: String?, userSlug: String?) {
        dataStore.edit { preferences ->
            if (username.isNullOrBlank()) {
                preferences.remove(usernameKey)
            } else {
                preferences[usernameKey] = username
            }
            if (userSlug.isNullOrBlank()) {
                preferences.remove(userSlugKey)
            } else {
                preferences[userSlugKey] = userSlug
            }
        }
    }

    suspend fun saveDeviceFlow(data: TraktDeviceCodeResponseDto) {
        val now = System.currentTimeMillis()
        dataStore.edit { preferences ->
            preferences[deviceCodeKey] = data.deviceCode
            preferences[userCodeKey] = data.userCode
            preferences[verificationUrlKey] = data.verificationUrl
            preferences[expiresAtKey] = now + (data.expiresIn * 1000L)
            preferences[pollIntervalKey] = data.interval
        }
    }

    suspend fun updatePollInterval(seconds: Int) {
        dataStore.edit { preferences ->
            preferences[pollIntervalKey] = seconds
        }
    }

    suspend fun clearDeviceFlow() {
        dataStore.edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth() {
        dataStore.edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(tokenTypeKey)
            preferences.remove(createdAtKey)
            preferences.remove(expiresInKey)
            preferences.remove(sessionIdentityKey)
            preferences.remove(usernameKey)
            preferences.remove(userSlugKey)
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }
}
