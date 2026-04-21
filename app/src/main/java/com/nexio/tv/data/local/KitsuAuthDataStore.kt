package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.repository.KitsuAuthSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface KitsuAuthStore {
    val state: Flow<KitsuAuthSnapshot>
    suspend fun save(snapshot: KitsuAuthSnapshot)
    suspend fun clear()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class KitsuAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : KitsuAuthStore {
    companion object {
        private const val FEATURE = "kitsu_auth_store"
    }

    private val usernameKey = stringPreferencesKey("username")
    private val enabledKey = booleanPreferencesKey("enabled")
    private val includeNsfwKey = booleanPreferencesKey("include_nsfw")
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val expiresAtKey = longPreferencesKey("expires_at_epoch_seconds")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    fun stateForProfile(profileId: Int): Flow<KitsuAuthSnapshot> = store(profileId).data.map { preferences ->
        KitsuAuthSnapshot(
            enabled = preferences[enabledKey] ?: true,
            username = preferences[usernameKey],
            accessToken = preferences[accessTokenKey],
            refreshToken = preferences[refreshTokenKey],
            expiresAtEpochSeconds = preferences[expiresAtKey],
            includeNsfw = preferences[includeNsfwKey] ?: false,
            password = null
        )
    }

    override val state: Flow<KitsuAuthSnapshot> = profileManager.activeProfileId.flatMapLatest { profileId ->
        stateForProfile(profileId)
    }

    override suspend fun save(snapshot: KitsuAuthSnapshot) {
        store().edit { preferences ->
            preferences[enabledKey] = snapshot.enabled
            preferences[includeNsfwKey] = snapshot.includeNsfw
            val username = snapshot.username?.trim().orEmpty()
            if (username.isBlank()) preferences.remove(usernameKey) else preferences[usernameKey] = username
            val accessToken = snapshot.accessToken.orEmpty()
            if (accessToken.isBlank()) preferences.remove(accessTokenKey) else preferences[accessTokenKey] = accessToken
            val refreshToken = snapshot.refreshToken.orEmpty()
            if (refreshToken.isBlank()) preferences.remove(refreshTokenKey) else preferences[refreshTokenKey] = refreshToken
            val expiresAt = snapshot.expiresAtEpochSeconds
            if (expiresAt == null) preferences.remove(expiresAtKey) else preferences[expiresAtKey] = expiresAt
        }
    }

    override suspend fun clear() {
        store().edit { preferences ->
            preferences.remove(accessTokenKey)
            preferences.remove(refreshTokenKey)
            preferences.remove(expiresAtKey)
        }
    }
}
