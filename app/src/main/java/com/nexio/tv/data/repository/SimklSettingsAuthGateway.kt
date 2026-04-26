package com.nexio.tv.data.repository

import javax.inject.Inject

class SimklSettingsAuthGateway @Inject constructor(
    private val authService: SimklAuthService
) {
    fun hasRequiredCredentials(): Boolean = authService.hasRequiredCredentials()

    suspend fun startPinAuth() = authService.startPinAuth()

    suspend fun pollPin(): SimklTokenPollResult = authService.pollPin()

    suspend fun revokeAndLogout() = authService.revokeAndLogout()

    suspend fun fetchUserSettings() = authService.fetchUserSettings()
}
