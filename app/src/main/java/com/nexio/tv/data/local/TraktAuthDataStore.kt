package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class TraktAuthState(
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
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
@OptIn(ExperimentalCoroutinesApi::class)
class TraktAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trakt_auth_store"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")

    private val usernameKey = stringPreferencesKey("username")
    private val userSlugKey = stringPreferencesKey("user_slug")

    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    fun stateForProfile(profileId: Int): Flow<TraktAuthState> = store(profileId).data.map { preferences ->
        TraktAuthState(
            accessToken = preferences[accessTokenKey],
            refreshToken = preferences[refreshTokenKey],
            tokenType = preferences[tokenTypeKey],
            createdAt = preferences[createdAtKey],
            expiresIn = preferences[expiresInKey],
            username = preferences[usernameKey],
            userSlug = preferences[userSlugKey],
            deviceCode = preferences[deviceCodeKey],
            userCode = preferences[userCodeKey],
            verificationUrl = preferences[verificationUrlKey],
            expiresAt = preferences[expiresAtKey],
            pollInterval = preferences[pollIntervalKey]
        )
    }

    val state: Flow<TraktAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        stateForProfile(profileId)
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    suspend fun saveToken(
        token: TraktTokenResponseDto,
        profileId: Int = profileManager.activeProfileId.value,
        clearAccountIdentity: Boolean = false
    ) {
        store(profileId).edit { preferences ->
            preferences[accessTokenKey] = token.accessToken
            preferences[refreshTokenKey] = token.refreshToken
            preferences[tokenTypeKey] = token.tokenType
            preferences[createdAtKey] = token.createdAt
            preferences[expiresInKey] = token.expiresIn
            if (clearAccountIdentity) {
                preferences.remove(usernameKey)
                preferences.remove(userSlugKey)
            }
        }
    }

    suspend fun saveUser(
        username: String?,
        userSlug: String?,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
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

    suspend fun saveDeviceFlow(
        data: TraktDeviceCodeResponseDto,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val now = System.currentTimeMillis()
        store(profileId).edit { preferences ->
            preferences[deviceCodeKey] = data.deviceCode
            preferences[userCodeKey] = data.userCode
            preferences[verificationUrlKey] = data.verificationUrl
            preferences[expiresAtKey] = now + (data.expiresIn * 1000L)
            preferences[pollIntervalKey] = data.interval
        }
    }

    suspend fun updatePollInterval(
        seconds: Int,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            preferences[pollIntervalKey] = seconds
        }
    }

    suspend fun clearDeviceFlow(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }

    suspend fun clearAuth(profileId: Int = profileManager.activeProfileId.value) {
        store(profileId).edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(tokenTypeKey)
            preferences.remove(createdAtKey)
            preferences.remove(expiresInKey)
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
