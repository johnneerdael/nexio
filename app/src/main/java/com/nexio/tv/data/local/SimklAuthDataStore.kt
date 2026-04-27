package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.remote.dto.simkl.SimklPinResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class SimklAuthState(
    val accessToken: String? = null,
    val username: String? = null,
    val accountId: Long? = null,
    val accountType: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null,
    val pollInterval: Int? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank()
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SimklAuthDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "simkl_auth_store"
    }

    private val accessTokenKey = stringPreferencesKey("access_token")
    private val usernameKey = stringPreferencesKey("username")
    private val accountIdKey = longPreferencesKey("account_id")
    private val accountTypeKey = stringPreferencesKey("account_type")
    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")
    private val pollIntervalKey = intPreferencesKey("poll_interval")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    fun stateForProfile(profileId: Int): Flow<SimklAuthState> = store(profileId).data.map { preferences ->
        SimklAuthState(
            accessToken = preferences[accessTokenKey],
            username = preferences[usernameKey],
            accountId = preferences[accountIdKey],
            accountType = preferences[accountTypeKey],
            deviceCode = preferences[deviceCodeKey],
            userCode = preferences[userCodeKey],
            verificationUrl = preferences[verificationUrlKey],
            expiresAt = preferences[expiresAtKey],
            pollInterval = preferences[pollIntervalKey]
        )
    }

    val state: Flow<SimklAuthState> = profileManager.activeProfileId.flatMapLatest { profileId ->
        stateForProfile(profileId)
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }
    val isEffectivelyAuthenticated: Flow<Boolean> = isAuthenticated

    suspend fun saveAccessToken(
        accessToken: String,
        profileId: Int = profileManager.activeProfileId.value,
        clearAccountIdentity: Boolean = false
    ) {
        store(profileId).edit { preferences ->
            preferences[accessTokenKey] = accessToken
            if (clearAccountIdentity) {
                preferences.remove(usernameKey)
                preferences.remove(accountIdKey)
                preferences.remove(accountTypeKey)
            }
        }
    }

    suspend fun saveUser(
        username: String?,
        accountId: Long?,
        accountType: String?,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            if (username.isNullOrBlank()) preferences.remove(usernameKey) else preferences[usernameKey] = username
            if (accountId == null) preferences.remove(accountIdKey) else preferences[accountIdKey] = accountId
            if (accountType.isNullOrBlank()) preferences.remove(accountTypeKey) else preferences[accountTypeKey] = accountType
        }
    }

    suspend fun saveDeviceFlow(
        data: SimklPinResponseDto,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        val now = System.currentTimeMillis()
        store(profileId).edit { preferences ->
            data.deviceCode?.let { preferences[deviceCodeKey] = it }
            data.userCode?.let { preferences[userCodeKey] = it }
            data.verificationUrl?.let { preferences[verificationUrlKey] = it }
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
            preferences.remove(usernameKey)
            preferences.remove(accountIdKey)
            preferences.remove(accountTypeKey)
            preferences.remove(deviceCodeKey)
            preferences.remove(userCodeKey)
            preferences.remove(verificationUrlKey)
            preferences.remove(expiresAtKey)
            preferences.remove(pollIntervalKey)
        }
    }
}
