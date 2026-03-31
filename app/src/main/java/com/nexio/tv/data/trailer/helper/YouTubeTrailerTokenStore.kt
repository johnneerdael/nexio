package com.nexio.tv.data.trailer.helper

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.youTubeTrailerTokenDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "youtube_trailer_token"
)

data class YouTubeTrailerTokenSession(
    val accessToken: String,
    val refreshToken: String? = null,
    val tokenType: String = "Bearer",
    val expiresInSeconds: Int? = null,
    val delegatedPageId: String? = null,
    val authUser: String? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)

data class YouTubeTrailerTokenState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val expiresInSeconds: Int? = null,
    val createdAtEpochMs: Long? = null,
    val delegatedPageId: String? = null,
    val authUser: String? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()

    val expiresAtEpochMs: Long?
        get() = createdAtEpochMs?.let { createdAt ->
            expiresInSeconds?.let { createdAt + (it * 1000L) }
        }
}

@Singleton
class YouTubeTrailerTokenStore internal constructor(
    private val dataStore: DataStore<Preferences>
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context.youTubeTrailerTokenDataStore)

    private val accessTokenKey = stringPreferencesKey("youtube_trailer_access_token")
    private val refreshTokenKey = stringPreferencesKey("youtube_trailer_refresh_token")
    private val tokenTypeKey = stringPreferencesKey("youtube_trailer_token_type")
    private val expiresInSecondsKey = intPreferencesKey("youtube_trailer_expires_in_seconds")
    private val createdAtEpochMsKey = longPreferencesKey("youtube_trailer_created_at_epoch_ms")
    private val delegatedPageIdKey = stringPreferencesKey("youtube_trailer_delegated_page_id")
    private val authUserKey = stringPreferencesKey("youtube_trailer_auth_user")

    val state: Flow<YouTubeTrailerTokenState> = dataStore.data.map { preferences ->
        YouTubeTrailerTokenState(
            accessToken = preferences[accessTokenKey],
            refreshToken = preferences[refreshTokenKey],
            tokenType = preferences[tokenTypeKey],
            expiresInSeconds = preferences[expiresInSecondsKey],
            createdAtEpochMs = preferences[createdAtEpochMsKey],
            delegatedPageId = preferences[delegatedPageIdKey],
            authUser = preferences[authUserKey]
        )
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    suspend fun saveSession(session: YouTubeTrailerTokenSession) {
        dataStore.edit { preferences ->
            preferences[accessTokenKey] = session.accessToken
            session.refreshToken?.let { preferences[refreshTokenKey] = it }
                ?: preferences.remove(refreshTokenKey)
            preferences[tokenTypeKey] = session.tokenType
            session.expiresInSeconds?.let { preferences[expiresInSecondsKey] = it }
                ?: preferences.remove(expiresInSecondsKey)
            preferences[createdAtEpochMsKey] = session.createdAtEpochMs
            session.delegatedPageId?.let { preferences[delegatedPageIdKey] = it }
                ?: preferences.remove(delegatedPageIdKey)
            session.authUser?.let { preferences[authUserKey] = it }
                ?: preferences.remove(authUserKey)
        }
    }

    suspend fun clearSession() {
        dataStore.edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(tokenTypeKey)
            preferences.remove(expiresInSecondsKey)
            preferences.remove(createdAtEpochMsKey)
            preferences.remove(delegatedPageIdKey)
            preferences.remove(authUserKey)
        }
    }

    suspend fun accessTokenHeader(): String? {
        return state.first().accessToken?.takeIf { it.isNotBlank() }?.let { "Bearer $it" }
    }
}
