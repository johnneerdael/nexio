package com.nexio.tv.data.repository

import javax.inject.Inject

class TraktSettingsAuthGateway @Inject constructor(
    private val authService: TraktAuthService
) {
    fun hasRequiredCredentials(): Boolean = authService.hasRequiredCredentials()

    suspend fun startDeviceAuth() = authService.startDeviceAuth()

    suspend fun pollDeviceToken(): TraktTokenPollResult = authService.pollDeviceToken()

    suspend fun revokeAndLogout(profileId: Int? = null) = authService.revokeAndLogout(profileId)

    suspend fun fetchUserSettings() = authService.fetchUserSettings()

    suspend fun getCurrentAuthState() = authService.getCurrentAuthState()
}
