package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCodeResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridDeviceCredentialsResponseDto
import com.nexio.tv.data.remote.dto.debrid.RealDebridTokenResponseDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.realDebridAuthDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "real_debrid_auth_store"
)

data class RealDebridAuthState(
    val userClientId: String? = null,
    val userClientSecret: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val tokenType: String? = null,
    val createdAt: Long? = null,
    val expiresIn: Int? = null,
    val username: String? = null,
    val deviceCode: String? = null,
    val userCode: String? = null,
    val verificationUrl: String? = null,
    val expiresAt: Long? = null
) {
    val isAuthenticated: Boolean
        get() = !userClientId.isNullOrBlank() &&
            !userClientSecret.isNullOrBlank() &&
            !accessToken.isNullOrBlank() &&
            !refreshToken.isNullOrBlank()
}

@Singleton
class RealDebridAuthDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val userClientIdKey = stringPreferencesKey("user_client_id")
    private val userClientSecretKey = stringPreferencesKey("user_client_secret")
    private val accessTokenKey = stringPreferencesKey("access_token")
    private val refreshTokenKey = stringPreferencesKey("refresh_token")
    private val tokenTypeKey = stringPreferencesKey("token_type")
    private val createdAtKey = longPreferencesKey("created_at")
    private val expiresInKey = intPreferencesKey("expires_in")
    private val usernameKey = stringPreferencesKey("username")
    private val deviceCodeKey = stringPreferencesKey("device_code")
    private val userCodeKey = stringPreferencesKey("user_code")
    private val verificationUrlKey = stringPreferencesKey("verification_url")
    private val expiresAtKey = longPreferencesKey("expires_at")

    val state: Flow<RealDebridAuthState> = context.realDebridAuthDataStore.data.map { prefs ->
        RealDebridAuthState(
            userClientId = prefs[userClientIdKey],
            userClientSecret = prefs[userClientSecretKey],
            accessToken = prefs[accessTokenKey],
            refreshToken = prefs[refreshTokenKey],
            tokenType = prefs[tokenTypeKey],
            createdAt = prefs[createdAtKey],
            expiresIn = prefs[expiresInKey],
            username = prefs[usernameKey],
            deviceCode = prefs[deviceCodeKey],
            userCode = prefs[userCodeKey],
            verificationUrl = prefs[verificationUrlKey],
            expiresAt = prefs[expiresAtKey]
        )
    }

    val isAuthenticated: Flow<Boolean> = state.map { it.isAuthenticated }

    suspend fun saveDeviceFlow(data: RealDebridDeviceCodeResponseDto) {
        val now = System.currentTimeMillis()
        context.realDebridAuthDataStore.edit { prefs ->
            prefs[deviceCodeKey] = data.deviceCode
            prefs[userCodeKey] = data.userCode
            prefs[verificationUrlKey] = data.directVerificationUrl ?: data.verificationUrl
            prefs[expiresAtKey] = now + (data.expiresIn * 1000L)
        }
    }

    suspend fun saveUserCredentials(data: RealDebridDeviceCredentialsResponseDto) {
        context.realDebridAuthDataStore.edit { prefs ->
            prefs[userClientIdKey] = data.clientId
            prefs[userClientSecretKey] = data.clientSecret
        }
    }

    suspend fun saveToken(token: RealDebridTokenResponseDto) {
        context.realDebridAuthDataStore.edit { prefs ->
            prefs[accessTokenKey] = token.accessToken
            prefs[refreshTokenKey] = token.refreshToken
            prefs[tokenTypeKey] = token.tokenType
            prefs[createdAtKey] = System.currentTimeMillis()
            prefs[expiresInKey] = token.expiresIn
        }
    }

    suspend fun saveUsername(username: String?) {
        context.realDebridAuthDataStore.edit { prefs ->
            if (username.isNullOrBlank()) {
                prefs.remove(usernameKey)
            } else {
                prefs[usernameKey] = username
            }
        }
    }

    suspend fun clearDeviceFlow() {
        context.realDebridAuthDataStore.edit { prefs ->
            prefs.remove(deviceCodeKey)
            prefs.remove(userCodeKey)
            prefs.remove(verificationUrlKey)
            prefs.remove(expiresAtKey)
        }
    }

    suspend fun clearAuth() {
        context.realDebridAuthDataStore.edit { prefs ->
            prefs.clear()
        }
    }
}
