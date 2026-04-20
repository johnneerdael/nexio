package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuAuthDataStore
import com.nexio.tv.data.local.KitsuAuthStore
import com.nexio.tv.data.remote.api.KitsuAuthApi
import com.nexio.tv.data.remote.api.KitsuTokenRequest
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

data class KitsuAuthSnapshot(
    val enabled: Boolean = false,
    val username: String? = null,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val includeNsfw: Boolean = false,
    val password: String? = null
) {
    val isAuthenticated: Boolean
        get() = !accessToken.isNullOrBlank() && !refreshToken.isNullOrBlank()
}

@Singleton
class KitsuAuthService(
    private val api: KitsuAuthApi,
    private val authStore: KitsuAuthStore,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    @Inject
    constructor(
        api: KitsuAuthApi,
        authDataStore: KitsuAuthDataStore
    ) : this(api = api, authStore = authDataStore)

    suspend fun login(username: String, password: String): Boolean {
        val normalizedUsername = username.trim()
        if (normalizedUsername.isBlank() || password.isBlank()) return false
        val response = api.token(
            KitsuTokenRequest(
                grantType = "password",
                username = normalizedUsername,
                password = password
            )
        )
        val body = response.takeIf { it.isSuccessful }?.body() ?: return false
        val accessToken = body.accessToken ?: return false
        val refreshToken = body.refreshToken ?: return false
        authStore.save(
            KitsuAuthSnapshot(
                username = normalizedUsername,
                enabled = true,
                accessToken = accessToken,
                refreshToken = refreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + (body.expiresIn ?: 0L),
                password = null
            )
        )
        return true
    }

    suspend fun validAccessToken(): String? {
        val current = authStore.state.first()
        val accessToken = current.accessToken?.takeIf { it.isNotBlank() } ?: return null
        val refreshToken = current.refreshToken?.takeIf { it.isNotBlank() } ?: return null
        val expiresAt = current.expiresAtEpochSeconds ?: 0L
        if (expiresAt > nowEpochSeconds() + REFRESH_SKEW_SECONDS) return accessToken

        val response = api.token(
            KitsuTokenRequest(
                grantType = "refresh_token",
                refreshToken = refreshToken
            )
        )
        val body = response.takeIf { it.isSuccessful }?.body() ?: return accessToken
        val newAccessToken = body.accessToken ?: return accessToken
        val newRefreshToken = body.refreshToken ?: refreshToken
        authStore.save(
            current.copy(
                accessToken = newAccessToken,
                refreshToken = newRefreshToken,
                expiresAtEpochSeconds = nowEpochSeconds() + (body.expiresIn ?: 0L),
                password = null
            )
        )
        return newAccessToken
    }

    suspend fun providerEnabled(): Boolean {
        return authStore.state.first().enabled
    }

    suspend fun disconnect() {
        authStore.clear()
    }

    companion object {
        private const val REFRESH_SKEW_SECONDS = 300L
    }
}
