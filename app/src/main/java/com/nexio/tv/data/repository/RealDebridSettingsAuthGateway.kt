package com.nexio.tv.data.repository

import javax.inject.Inject

class RealDebridSettingsAuthGateway @Inject constructor(
    private val authService: RealDebridAuthService
) {
    suspend fun startDeviceAuth() = authService.startDeviceAuth()

    suspend fun pollDeviceToken(): RealDebridTokenPollResult = authService.pollDeviceToken()

    suspend fun revokeAndLogout() = authService.revokeAndLogout()
}
